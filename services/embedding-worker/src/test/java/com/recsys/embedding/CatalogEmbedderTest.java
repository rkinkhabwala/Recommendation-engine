package com.recsys.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.recsys.events.v1.CatalogItem;
import com.recsys.events.v1.Domain;
import com.recsys.events.v1.ItemEmbedding;
import com.recsys.openai.EmbeddingClient;
import com.recsys.openai.EmbeddingResponse;
import com.recsys.openai.JobPriority;
import com.recsys.openai.MockEmbeddingClient;
import com.recsys.openai.OpenAiException;
import com.recsys.vector.InMemoryVectorIndex;
import com.recsys.vector.SearchFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CatalogEmbedderTest {
  static final String INDEX = "items_mock_64_v1";
  final InMemoryVectorIndex index = new InMemoryVectorIndex();
  final List<ItemEmbedding> published = new ArrayList<>();
  final List<String> tombstones = new ArrayList<>();
  final List<String> dlq = new ArrayList<>();
  final AtomicInteger apiCalls = new AtomicInteger();
  final WorkerProperties props =
      new WorkerProperties(
          INDEX,
          "items_current",
          "song-v1",
          2,
          "x",
          0,
          Duration.ofSeconds(1),
          Duration.ofDays(30),
          null);

  final CatalogEmbedder.EmbeddingSink sink =
      new CatalogEmbedder.EmbeddingSink() {
        public void publish(List<ItemEmbedding> e) {
          published.addAll(e);
        }

        public void tombstone(String id) {
          tombstones.add(id);
        }

        public void deadLetter(String id, String reason) {
          dlq.add(id);
        }
      };

  /** Counts calls and rejects inputs containing "POISON" like a 400 would. */
  EmbeddingClient client(boolean outage) {
    var mock = new MockEmbeddingClient("mock", 64, null);
    return new EmbeddingClient() {
      public EmbeddingResponse embed(List<String> inputs, String job, JobPriority p) {
        apiCalls.incrementAndGet();
        if (outage) {
          throw new OpenAiException(OpenAiException.Kind.RETRYABLE, "503");
        }
        if (inputs.stream().anyMatch(s -> s.contains("POISON"))) {
          throw new OpenAiException(OpenAiException.Kind.BAD_INPUT, "400");
        }
        return mock.embed(inputs, job, p);
      }

      public String model() {
        return "mock";
      }

      public int dimensions() {
        return 64;
      }
    };
  }

  CatalogEmbedder embedder(boolean outage) {
    index.ensureCollection(INDEX, 64, "items_current");
    return new CatalogEmbedder(
        client(outage), index, sink, props, new SimpleMeterRegistry(), Clock.systemUTC());
  }

  public static CatalogItem song(String id, String title, List<String> regions) {
    return CatalogItem.newBuilder()
        .setItemId(id)
        .setDomain(Domain.SONG)
        .setTitle(title)
        .setCreatorId("a1")
        .setCreatorName("Artist")
        .setGenres(List.of("jazz"))
        .setMoodTags(List.of("calm"))
        .setAvailableRegions(regions)
        .setCreatedAt(Instant.EPOCH)
        .setUpdatedAt(Instant.EPOCH)
        .setSeq(1)
        .build();
  }

  static Map<String, CatalogItem> batch(CatalogItem... items) {
    Map<String, CatalogItem> m = new LinkedHashMap<>();
    for (var i : items) {
      m.put(i.getItemId(), i);
    }
    return m;
  }

  @Test
  void embedsOnlyWhenContentChanges() {
    var e = embedder(false);
    e.process(
        batch(
            song("s1", "Blue", List.of()),
            song("s2", "Green", List.of()),
            song("s3", "Red", List.of())));
    assertThat(published).hasSize(3);
    assertThat(apiCalls).hasValue(2); // batch size 2
    assertThat(index.size(INDEX)).isEqualTo(3);

    published.clear();
    apiCalls.set(0);
    e.process(batch(song("s1", "Blue", List.of("US")))); // only regions changed
    assertThat(apiCalls).hasValue(0);
    assertThat(published).isEmpty();
    var hits =
        index.search(
            "items_current",
            new float[64],
            new SearchFilter("song", "GB", true, null, List.of()),
            10,
            null);
    assertThat(hits).extracting(h -> h.itemId()).doesNotContain("s1"); // payload refreshed

    e.process(batch(song("s1", "Blue (Live)", List.of())));
    assertThat(apiCalls).hasValue(1);
    assertThat(published).extracting(ItemEmbedding::getItemId).containsExactly("s1");
  }

  @Test
  void poisonItemsAreDeadLetteredOthersProceed() {
    embedder(false).process(batch(song("s1", "Fine", List.of()), song("s2", "POISON", List.of())));
    assertThat(dlq).containsExactly("s2");
    assertThat(published).extracting(ItemEmbedding::getItemId).containsExactly("s1");
  }

  @Test
  void outagesAreRetriedNotDeadLettered() {
    assertThatThrownBy(() -> embedder(true).process(batch(song("s1", "Blue", List.of()))))
        .isInstanceOf(OpenAiException.class);
    assertThat(dlq).isEmpty();
    assertThat(published).isEmpty();
  }

  @Test
  void deletionsRemovePointsAndTombstone() {
    var e = embedder(false);
    e.process(batch(song("s1", "Blue", List.of())));
    Map<String, CatalogItem> del = new HashMap<>();
    del.put("s1", null);
    e.process(del);
    assertThat(index.size(INDEX)).isZero();
    assertThat(tombstones).containsExactly("s1");
  }

  @Test
  void hashCoversTemplateModelAndDims() {
    String text = "x";
    assertThat(ItemText.hash("song-v1", "m", 512, text))
        .isNotEqualTo(ItemText.hash("song-v2", "m", 512, text));
    assertThat(ItemText.hash("song-v1", "m", 512, text))
        .isNotEqualTo(ItemText.hash("song-v1", "m", 1536, text));
    assertThat(ItemText.of(song("s", "Blue", List.of())))
        .isEqualTo("Blue by Artist. Genres: jazz. Mood: calm.");
  }
}
