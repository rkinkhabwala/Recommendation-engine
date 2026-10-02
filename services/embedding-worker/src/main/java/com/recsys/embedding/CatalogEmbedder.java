package com.recsys.embedding;

import com.recsys.common.Vectors;
import com.recsys.events.v1.CatalogItem;
import com.recsys.events.v1.ItemEmbedding;
import com.recsys.openai.EmbeddingClient;
import com.recsys.openai.EmbeddingResponse;
import com.recsys.openai.JobPriority;
import com.recsys.openai.OpenAiException;
import com.recsys.vector.IndexedItem;
import com.recsys.vector.ItemPayload;
import com.recsys.vector.VectorIndex;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Embeds new/changed catalog items and writes them to the vector index and {@code
 * catalog.embeddings.v1}. Unchanged content (same hash) skips the OpenAI call but still refreshes
 * filter payload (e.g. region availability). Writes are idempotent (deterministic point ids,
 * compacted topic), so at-least-once redelivery is safe.
 *
 * <p>Failure policy: bad input is dead-lettered per item; anything retryable (429, 5xx, open
 * circuit, auth/config) is thrown so the consumer backs off and retries the batch. An OpenAI outage
 * therefore shows up as consumer lag that drains on recovery, not as a flood of DLQ records.
 */
public final class CatalogEmbedder {
  private static final Logger log = LoggerFactory.getLogger(CatalogEmbedder.class);

  public interface EmbeddingSink {
    void publish(List<ItemEmbedding> embeddings);

    void tombstone(String itemId);

    void deadLetter(String itemId, String reason);
  }

  private final EmbeddingClient client;
  private final VectorIndex index;
  private final EmbeddingSink sink;
  private final WorkerProperties props;
  private final MeterRegistry metrics;
  private final Clock clock;

  public CatalogEmbedder(
      EmbeddingClient client,
      VectorIndex index,
      EmbeddingSink sink,
      WorkerProperties props,
      MeterRegistry metrics,
      Clock clock) {
    this.client = client;
    this.index = index;
    this.sink = sink;
    this.props = props;
    this.metrics = metrics;
    this.clock = clock;
  }

  /**
   * @param batch itemId → latest catalog version in this batch (null value = deleted)
   */
  public void process(Map<String, CatalogItem> batch) {
    String collection = props.indexVersion();
    List<String> deleted = new ArrayList<>();
    Map<String, CatalogItem> live = new LinkedHashMap<>();
    batch.forEach(
        (id, item) -> {
          if (item == null) {
            deleted.add(id);
          } else {
            live.put(id, item);
          }
        });
    if (!deleted.isEmpty()) {
      index.delete(collection, deleted);
      deleted.forEach(sink::tombstone);
      count("deleted", deleted.size());
    }
    if (live.isEmpty()) {
      return;
    }

    Map<String, String> texts = new LinkedHashMap<>();
    Map<String, String> hashes = new LinkedHashMap<>();
    live.forEach(
        (id, item) -> {
          String text = ItemText.of(item);
          texts.put(id, text);
          hashes.put(
              id,
              ItemText.hash(props.templateVersion(), client.model(), client.dimensions(), text));
        });
    Map<String, String> existing = index.contentHashes(collection, List.copyOf(live.keySet()));

    List<String> toEmbed = new ArrayList<>();
    for (var id : live.keySet()) {
      if (hashes.get(id).equals(existing.get(id))) {
        index.setPayload(collection, payload(live.get(id), hashes.get(id)));
        count("skipped_unchanged", 1);
      } else {
        toEmbed.add(id);
      }
    }
    for (int from = 0; from < toEmbed.size(); from += props.embedBatchSize()) {
      List<String> ids =
          toEmbed.subList(from, Math.min(toEmbed.size(), from + props.embedBatchSize()));
      embedAndWrite(ids, live, texts, hashes, collection);
    }
  }

  private void embedAndWrite(
      List<String> ids,
      Map<String, CatalogItem> live,
      Map<String, String> texts,
      Map<String, String> hashes,
      String collection) {
    Map<String, float[]> vectors = new LinkedHashMap<>();
    try {
      EmbeddingResponse res =
          client.embed(ids.stream().map(texts::get).toList(), "catalog", JobPriority.ESSENTIAL);
      for (int i = 0; i < ids.size(); i++) {
        vectors.put(ids.get(i), res.vectors().get(i));
      }
    } catch (OpenAiException e) {
      if (e.kind() != OpenAiException.Kind.BAD_INPUT) {
        throw e; // retry the whole batch later
      }
      // Isolate the poison item(s): embed one by one.
      embedIndividually(ids, texts, vectors::put);
    }
    if (vectors.isEmpty()) {
      return;
    }
    Instant now = clock.instant();
    List<ItemEmbedding> records = new ArrayList<>();
    List<IndexedItem> points = new ArrayList<>();
    vectors.forEach(
        (id, v) -> {
          CatalogItem item = live.get(id);
          records.add(
              ItemEmbedding.newBuilder()
                  .setItemId(id)
                  .setDomain(item.getDomain())
                  .setIndexVersion(collection)
                  .setModel(client.model())
                  .setDims(client.dimensions())
                  .setContentHash(hashes.get(id))
                  .setVector(Vectors.toList(v))
                  .setEmbeddedAt(now)
                  .build());
          points.add(new IndexedItem(payload(item, hashes.get(id)), v));
        });
    // Topic first, then index: if we crash in between, the redelivered item has no matching hash
    // in the index and is re-embedded (costs a call, never loses the embedding record).
    sink.publish(records);
    index.upsert(collection, points);
    count("embedded", records.size());
  }

  private void embedIndividually(
      List<String> ids, Map<String, String> texts, BiConsumer<String, float[]> out) {
    for (String id : ids) {
      try {
        out.accept(
            id,
            client
                .embed(List.of(texts.get(id)), "catalog", JobPriority.ESSENTIAL)
                .vectors()
                .get(0));
      } catch (OpenAiException e) {
        if (e.kind() != OpenAiException.Kind.BAD_INPUT) {
          throw e;
        }
        log.warn("Dead-lettering item {}: {}", id, e.getMessage());
        sink.deadLetter(id, e.getMessage());
        count("dlq", 1);
      }
    }
  }

  ItemPayload payload(CatalogItem item, String hash) {
    return new ItemPayload(
        item.getItemId(),
        item.getDomain().name().toLowerCase(Locale.ROOT),
        item.getCreatorId(),
        item.getGenres(),
        item.getMoodTags(),
        item.getExplicit(),
        item.getAvailableRegions(),
        item.getCreatedAt().toEpochMilli(),
        hash,
        props.indexVersion());
  }

  private void count(String result, int n) {
    metrics.counter("recs_embedding_items_total", "result", result).increment(n);
  }
}
