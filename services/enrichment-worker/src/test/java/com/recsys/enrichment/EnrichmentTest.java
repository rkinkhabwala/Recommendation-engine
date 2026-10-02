package com.recsys.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.recsys.events.v1.CatalogItem;
import com.recsys.events.v1.Domain;
import com.recsys.events.v1.RecommendationServed;
import com.recsys.events.v1.ServedItem;
import com.recsys.features.FeatureEnvelope;
import com.recsys.features.FeatureJson;
import com.recsys.features.InMemoryFeatureStore;
import com.recsys.features.RedisKeys;
import com.recsys.features.model.Explanation;
import com.recsys.features.model.ItemMeta;
import com.recsys.openai.ChatClient;
import com.recsys.openai.JobPriority;
import com.recsys.openai.MockChatClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EnrichmentTest {
  final EnrichmentProperties props =
      new EnrichmentProperties(
          1, List.of("song", "book", "video", "post"), "x", "k", 100, 3, Duration.ofDays(30), 0);
  final List<String> prompts = new ArrayList<>();
  final ChatClient mock = new MockChatClient(MockLlm.handlers(), null);
  final ChatClient spy =
      new ChatClient() {
        public StructuredResponse complete(StructuredRequest r, String job, JobPriority p) {
          prompts.add(r.system() + "\n" + r.user());
          assertThat(r.schema().path("additionalProperties").asBoolean(true)).isFalse();
          return mock.complete(r, job, p);
        }

        public String model() {
          return "mock-llm";
        }
      };

  static CatalogItem post(String id, String text, Integer version) {
    return CatalogItem.newBuilder()
        .setItemId(id)
        .setDomain(Domain.POST)
        .setTitle("t")
        .setCreatorId("poster1")
        .setCreatorName("Jane Q")
        .setGenres(List.of("jazz"))
        .setDescription(text)
        .setEnrichmentVersion(version)
        .setCreatedAt(Instant.EPOCH)
        .setUpdatedAt(Instant.EPOCH)
        .setSeq(1)
        .build();
  }

  @Test
  void enrichesMissingMetadataWithValidatedStructuredOutput() {
    Map<String, EnrichmentValidator.Valid> applied = new HashMap<>();
    var enricher =
        new ItemEnricher(
            spy, (id, e) -> applied.put(id, e) == null, props, new SimpleMeterRegistry());

    enricher.process(post("p1", "A midnight history guide to the quiet clubs of jazz", null));
    enricher.process(post("p2", "Already enriched", 1)); // current version: skipped

    assertThat(applied).containsOnlyKeys("p1");
    var e = applied.get("p1");
    assertThat(e.moods()).isNotEmpty().allMatch(Vocabulary.MOODS::contains);
    assertThat(e.topics()).contains("jazz");
    assertThat(e.tone()).isEqualTo("informative");
    assertThat(e.readingLevel()).isNotNull(); // posts get a reading level
    assertThat(prompts).hasSize(1).allMatch(p -> !p.contains("user_id"));
  }

  @Test
  void validatorEnforcesVocabulariesAndTagHygiene() throws Exception {
    var json =
        EnrichmentSchema.JSON.readTree(
            """
            {"moods":["calm","ecstatic"],"themes":["Night Life","visit https://x.io","a@b.com","night life"],
             "topics":["jazz"],"tone":"SARCASTIC","reading_level":"advanced"}""");
    var v = EnrichmentValidator.validate(json, 1, "song");
    assertThat(v.moods()).containsExactly("calm");
    assertThat(v.themes()).containsExactly("night life");
    assertThat(v.tone()).isNull();
    assertThat(v.readingLevel()).isNull(); // not a book/post
    assertThat(
            EnrichmentValidator.validate(
                EnrichmentSchema.JSON.readTree("{\"moods\":[]}"), 1, "song"))
        .isNull();
  }

  @Test
  void explanationsAreUserAgnosticCachedAndPublishedAsFeatures() {
    var store = new InMemoryFeatureStore();
    store.put(RedisKeys.itemMeta("s_2"), meta("s_2", "Blue Train"));
    store.put(RedisKeys.itemMeta("s_1"), meta("s_1", "So What"));
    Map<String, byte[]> published = new HashMap<>();
    var gen =
        new ExplanationGenerator(
            spy,
            store,
            published::put,
            props,
            Caffeine.newBuilder().build(),
            new SimpleMeterRegistry(),
            Clock.systemUTC());

    var served = served("rec-1", "u_secret_42");
    gen.process(served);
    gen.process(served("rec-2", "u_other")); // same (reason, seed, item): reuses the cached text

    String key = RedisKeys.explanation("song", "LISTENED_TOGETHER", "s_1", "s_2");
    assertThat(published).containsOnlyKeys(key);
    var expl =
        FeatureJson.read(FeatureEnvelope.decode(published.get(key)).dataBytes(), Explanation.class);
    assertThat(expl.text()).isEqualTo("People who enjoy So What often pick Blue Train too.");
    assertThat(prompts).hasSize(1).noneMatch(p -> p.contains("u_secret_42"));
  }

  static ItemMeta meta(String id, String title) {
    return new ItemMeta(
        id,
        "song",
        title,
        "a1",
        "Miles",
        List.of("jazz"),
        List.of("mellow"),
        null,
        null,
        false,
        List.of(),
        0);
  }

  static RecommendationServed served(String recId, String user) {
    return RecommendationServed.newBuilder()
        .setRecommendationId(recId)
        .setUserId(user)
        .setDomain(Domain.SONG)
        .setSurface("home")
        .setVariantId("control")
        .setRankerVersion("heuristic-v1")
        .setIndexVersion("items_mock_512_v1")
        .setFallbackLevel("NONE")
        .setServedTs(Instant.EPOCH)
        .setItems(
            List.of(
                ServedItem.newBuilder()
                    .setItemId("s_2")
                    .setPosition(0)
                    .setScore(1)
                    .setReasonCode("LISTENED_TOGETHER")
                    .setSeedItemId("s_1")
                    .build(),
                ServedItem.newBuilder()
                    .setItemId("s_9")
                    .setPosition(1)
                    .setScore(1)
                    .setReasonCode("POPULAR_FALLBACK")
                    .build()))
        .build();
  }
}
