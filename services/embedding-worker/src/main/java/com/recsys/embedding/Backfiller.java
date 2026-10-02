package com.recsys.embedding;

import com.recsys.common.Vectors;
import com.recsys.events.v1.CatalogItem;
import com.recsys.events.v1.ItemEmbedding;
import com.recsys.vector.IndexedItem;
import com.recsys.vector.ItemPayload;
import com.recsys.vector.VectorIndex;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Re-embeds the full catalog into a new, versioned index (model or dimension change) without
 * touching the live one (docs/architecture.md §3.6):
 *
 * <ol>
 *   <li>embed every live item into collection {@code targetIndex} and topic {@code
 *       catalog.embeddings.<targetIndex>} (Batch API in production: cheaper, asynchronous);
 *   <li>validate count parity;
 *   <li>optionally flip the serving alias. Stream processor and API then switch their configured
 *       index version / embeddings topic (shadow deployment), so user vectors are rebuilt in the
 *       new space before serving reads them.
 * </ol>
 */
public final class Backfiller {
  private static final Logger log = LoggerFactory.getLogger(Backfiller.class);

  /** Embeds a chunk: item id → text in, item id → unit vector out (failures absent). */
  public interface ChunkEmbedder extends Function<Map<String, String>, Map<String, float[]>> {}

  public interface EmbeddingPublisher {
    void publish(String topic, List<ItemEmbedding> records);
  }

  public record Result(int live, int embedded, int missing, boolean aliasSwitched) {}

  private final VectorIndex index;
  private final ChunkEmbedder embedder;
  private final EmbeddingPublisher publisher;
  private final Clock clock;

  public Backfiller(
      VectorIndex index, ChunkEmbedder embedder, EmbeddingPublisher publisher, Clock clock) {
    this.index = index;
    this.embedder = embedder;
    this.publisher = publisher;
    this.clock = clock;
  }

  public Result run(
      Map<String, CatalogItem> catalog,
      String targetIndex,
      int dims,
      String model,
      String templateVersion,
      String alias,
      boolean switchAlias,
      int chunkSize) {
    String topic = "catalog.embeddings." + targetIndex;
    // Ensure the target exists; the serving alias already exists, so it is left untouched here.
    index.ensureCollection(targetIndex, dims, alias);
    List<CatalogItem> live = catalog.values().stream().filter(java.util.Objects::nonNull).toList();
    int embedded = 0;
    for (int from = 0; from < live.size(); from += chunkSize) {
      List<CatalogItem> chunk = live.subList(from, Math.min(live.size(), from + chunkSize));
      Map<String, String> texts = new LinkedHashMap<>();
      chunk.forEach(i -> texts.put(i.getItemId(), ItemText.of(i)));
      Map<String, float[]> vectors = embedder.apply(texts);
      List<ItemEmbedding> records = new ArrayList<>();
      List<IndexedItem> points = new ArrayList<>();
      for (CatalogItem item : chunk) {
        float[] v = vectors.get(item.getItemId());
        if (v == null) {
          continue;
        }
        String hash = ItemText.hash(templateVersion, model, dims, texts.get(item.getItemId()));
        records.add(
            ItemEmbedding.newBuilder()
                .setItemId(item.getItemId())
                .setDomain(item.getDomain())
                .setIndexVersion(targetIndex)
                .setModel(model)
                .setDims(dims)
                .setContentHash(hash)
                .setVector(Vectors.toList(v))
                .setEmbeddedAt(clock.instant())
                .build());
        points.add(
            new IndexedItem(
                new ItemPayload(
                    item.getItemId(),
                    item.getDomain().name().toLowerCase(Locale.ROOT),
                    item.getCreatorId(),
                    item.getGenres(),
                    item.getMoodTags(),
                    item.getExplicit(),
                    item.getAvailableRegions(),
                    item.getCreatedAt().toEpochMilli(),
                    hash,
                    targetIndex),
                v));
      }
      publisher.publish(topic, records);
      index.upsert(targetIndex, points);
      embedded += records.size();
      log.info("Backfill {}: {}/{} embedded", targetIndex, embedded, live.size());
    }
    int missing = live.size() - embedded;
    boolean switched = false;
    if (switchAlias && missing == 0) {
      index.switchAlias(alias, targetIndex);
      switched = true;
      log.info("Alias {} now points at {}", alias, targetIndex);
    } else if (switchAlias) {
      log.error("Not switching alias: {} items failed to embed", missing);
    }
    return new Result(live.size(), embedded, missing, switched);
  }
}
