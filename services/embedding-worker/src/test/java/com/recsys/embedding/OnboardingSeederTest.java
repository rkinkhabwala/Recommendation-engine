package com.recsys.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import com.recsys.common.Vectors;
import com.recsys.events.v1.Domain;
import com.recsys.events.v1.OnboardingSubmitted;
import com.recsys.openai.EmbeddingClient;
import com.recsys.openai.EmbeddingResponse;
import com.recsys.openai.JobPriority;
import com.recsys.openai.MockEmbeddingClient;
import com.recsys.vector.InMemoryVectorIndex;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OnboardingSeederTest {
  @Test
  void seedsUserVectorFromScrubbedTextWithoutIdentifiers() throws Exception {
    List<String> sent = new ArrayList<>();
    var mock = new MockEmbeddingClient("mock", 64, null);
    EmbeddingClient spy =
        new EmbeddingClient() {
          public EmbeddingResponse embed(List<String> in, String job, JobPriority p) {
            sent.addAll(in);
            return mock.embed(in, job, p);
          }

          public String model() {
            return "mock";
          }

          public int dimensions() {
            return 64;
          }
        };
    var published = new java.util.HashMap<String, byte[]>();
    var props =
        new WorkerProperties(
            "items_mock_64_v1", "items_current", "song-v1", 10, "x", 0, null, Duration.ofDays(30));
    var seeder = new OnboardingSeeder(spy, new InMemoryVectorIndex(), published::put, props);

    seeder.seed(
        OnboardingSubmitted.newBuilder()
            .setUserId("u_42")
            .setDomain(Domain.SONG)
            .setGenres(List.of("jazz"))
            .setFreeText("mellow late night jazz, email me at me@example.com")
            .setSubmittedTs(Instant.parse("2026-10-02T10:00:00Z"))
            .build());

    assertThat(sent).noneMatch(s -> s.contains("u_42") || s.contains("example.com"));
    byte[] env = published.get(com.recsys.features.RedisKeys.userSeed("u_42"));
    var seed =
        com.recsys.features.FeatureJson.read(
            com.recsys.features.FeatureEnvelope.decode(env).dataBytes(),
            com.recsys.features.model.UserVector.class);
    assertThat(seed.source()).isEqualTo("onboarding");
    float[] jazz =
        mock.embed(List.of("Genres: jazz."), "t", JobPriority.ESSENTIAL).vectors().get(0);
    assertThat(Vectors.cosine(Vectors.fromFloat16(seed.vector()), jazz)).isGreaterThan(0.5f);
  }
}
