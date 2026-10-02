package com.recsys.embedding;

import com.recsys.common.Vectors;
import com.recsys.events.v1.OnboardingSubmitted;
import com.recsys.features.FeatureEnvelope;
import com.recsys.features.RedisKeys;
import com.recsys.features.model.UserVector;
import com.recsys.openai.EmbeddingClient;
import com.recsys.openai.JobPriority;
import com.recsys.openai.PiiScrubber;
import com.recsys.vector.VectorIndex;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cold start: turns onboarding answers into a seed user vector ({@code u:{id}:seed}). Picked items
 * contribute their stored vectors (no API call); genre picks and the free-text answer are embedded.
 * Free text is PII-scrubbed and sent without any user identifier. Until the seed exists the user
 * gets popular-in-region recommendations.
 *
 * <p>TODO(phase-3): creator picks → average of the creator's top items.
 */
public final class OnboardingSeeder {
  private final EmbeddingClient client;
  private final VectorIndex index;

  /** Publishes a feature record (Kafka key = Redis key). */
  @FunctionalInterface
  public interface SeedSink {
    void publish(String redisKey, byte[] envelope) throws Exception;
  }

  private final SeedSink sink;
  private final WorkerProperties props;

  public OnboardingSeeder(
      EmbeddingClient client, VectorIndex index, SeedSink sink, WorkerProperties props) {
    this.client = client;
    this.index = index;
    this.sink = sink;
    this.props = props;
  }

  public void seed(OnboardingSubmitted o) throws Exception {
    List<float[]> parts = new ArrayList<>();
    if (!o.getItemIds().isEmpty()) {
      Map<String, float[]> picked = index.vectors(props.indexVersion(), o.getItemIds());
      parts.addAll(picked.values());
    }
    List<String> texts = new ArrayList<>();
    if (!o.getGenres().isEmpty()) {
      texts.add("Genres: " + String.join(", ", o.getGenres()) + ".");
    }
    String freeText = PiiScrubber.scrub(o.getFreeText());
    if (freeText != null && !freeText.isBlank()) {
      texts.add(freeText);
    }
    if (!texts.isEmpty()) {
      parts.addAll(client.embed(texts, "onboarding", JobPriority.ESSENTIAL).vectors());
    }
    if (parts.isEmpty()) {
      return;
    }
    float[] sum = new float[client.dimensions()];
    for (float[] v : parts) {
      for (int i = 0; i < sum.length; i++) {
        sum[i] += v[i];
      }
    }
    float[] seed = Vectors.normalized(sum);
    if (seed == null) {
      return;
    }
    long ts = o.getSubmittedTs().toEpochMilli();
    var data =
        new UserVector(
            o.getUserId(), props.indexVersion(), Vectors.toFloat16(seed), "onboarding", ts);
    sink.publish(
        RedisKeys.userSeed(o.getUserId(), o.getDomain().name().toLowerCase(java.util.Locale.ROOT)),
        FeatureEnvelope.encode(ts, props.userKeyTtl().toSeconds(), ts, data));
  }
}
