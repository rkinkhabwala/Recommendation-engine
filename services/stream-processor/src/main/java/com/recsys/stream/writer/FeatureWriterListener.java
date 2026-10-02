package com.recsys.stream.writer;

import com.recsys.common.Topics;
import com.recsys.features.FeatureEnvelope;
import com.recsys.features.FeatureWriter;
import com.recsys.features.RedisKeys;
import com.recsys.features.WriteResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.AbstractConsumerSeekAware;
import org.springframework.stereotype.Component;

/**
 * Applies compacted feature topics to Redis. Exactly-once ends at Kafka, so every write is a
 * compare-and-set on the record's seq: replays after a crash or rebalance are no-ops, and an older
 * value can never overwrite a newer one. Tombstones delete; a user tombstone deletes all of that
 * user's keys and sets the deletion marker. Failures throw so the batch is retried with backoff
 * (the error handler never skips), which stalls only this consumer, not the topology.
 */
@Component
public class FeatureWriterListener extends AbstractConsumerSeekAware {
  private final FeatureWriter writer;
  private final Duration markerTtl;
  private final MeterRegistry registry;

  public FeatureWriterListener(FeatureWriter writer, MeterRegistry registry) {
    this.writer = writer;
    this.registry = registry;
    this.markerTtl = Duration.ofDays(30);
  }

  @KafkaListener(
      id = "feature-writer",
      topics = {Topics.FEATURES_USER, Topics.FEATURES_ITEM, Topics.FEATURES_TRENDING},
      batch = "true")
  public void onBatch(List<ConsumerRecord<String, byte[]>> records) throws Exception {
    List<CompletableFuture<?>> pending = new ArrayList<>(records.size());
    Set<String> deletedUsers = new HashSet<>();
    long now = System.currentTimeMillis();
    for (ConsumerRecord<String, byte[]> r : records) {
      String key = r.key();
      if (r.value() == null) {
        String userId = RedisKeys.userIdOf(key);
        if (userId == null) {
          pending.add(writer.delete(key));
        } else if (deletedUsers.add(userId)) {
          pending.add(writer.deleteUser(userId, markerTtl));
        }
        continue;
      }
      FeatureEnvelope env = FeatureEnvelope.decode(r.value());
      long sourceTs = env.sourceTs();
      pending.add(
          writer
              .upsert(key, env.seq(), env.dataBytes(), env.ttlSeconds())
              .thenAccept(
                  result -> {
                    result(result, key);
                    if (result == WriteResult.APPLIED && sourceTs > 0) {
                      freshness(key).record(Math.max(0, now - sourceTs), TimeUnit.MILLISECONDS);
                    }
                  }));
    }
    writer.awaitAll(pending, Duration.ofSeconds(10));
  }

  /**
   * Freshness = newest event's receive time → feature applied in Redis, tagged by feature. The 5 s
   * SLO applies to {@code feature="user_short_term"}; item features and the long-term vector are
   * throttled by design (30 s / interval) and are reported separately.
   */
  private Timer freshness(String key) {
    return Timer.builder("recs_feature_freshness")
        .description("Event receive time -> feature applied in Redis")
        .tag("feature", feature(key))
        .publishPercentileHistogram()
        .serviceLevelObjectives(Duration.ofSeconds(1), Duration.ofSeconds(5))
        .register(registry);
  }

  static String feature(String key) {
    if (key.endsWith(":st")) {
      return "user_short_term";
    }
    if (key.endsWith(":lt")) {
      return "user_long_term";
    }
    if (key.endsWith(":seed")) {
      return "user_seed";
    }
    int i = key.lastIndexOf(':');
    return key.startsWith("trend:") ? "trending" : "item_" + key.substring(i + 1);
  }

  /** Seeks every assigned partition back to the beginning (Redis rebuild). */
  public void rewindAll() {
    seekToBeginning();
  }

  private void result(WriteResult result, String key) {
    String kind = key.startsWith("u:") ? "user" : key.startsWith("trend:") ? "trending" : "item";
    Counter.builder("recs_feature_writes_total")
        .tag("result", result.name().toLowerCase())
        .tag("kind", kind)
        .register(registry)
        .increment();
  }
}
