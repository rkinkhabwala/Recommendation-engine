package com.recsys.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.recsys.common.Topics;
import com.recsys.features.FeatureEnvelope;
import com.recsys.features.InMemoryFeatureStore;
import com.recsys.features.RedisKeys;
import com.recsys.features.model.ItemStats;
import com.recsys.features.model.UserVector;
import com.recsys.stream.writer.FeatureWriterListener;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class FeatureWriterListenerTest {
  final InMemoryFeatureStore store = new InMemoryFeatureStore();
  final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  final FeatureWriterListener listener = new FeatureWriterListener(store, registry);

  static ConsumerRecord<String, byte[]> rec(String topic, String key, byte[] value) {
    return new ConsumerRecord<>(topic, 0, 0, key, value);
  }

  static byte[] stats(long seq, double ctr) {
    return FeatureEnvelope.encode(
        seq, 0, System.currentTimeMillis(), new ItemStats("s1", 1, 1, ctr, 0.5, 0.1, 1, 1, 0));
  }

  @Test
  void replayedBatchesNeverRegressState() throws Exception {
    String key = RedisKeys.itemStats("s1");
    listener.onBatch(
        List.of(
            rec(Topics.FEATURES_ITEM, key, stats(2, 0.2)),
            rec(Topics.FEATURES_ITEM, key, stats(3, 0.3))));
    // Consumer restarts from an older offset (rebalance / crash before commit).
    listener.onBatch(List.of(rec(Topics.FEATURES_ITEM, key, stats(2, 0.2))));

    assertThat(store.seq(key)).isEqualTo(3);
    assertThat(store.itemStats(List.of("s1"), Duration.ofSeconds(1)).get("s1").ctr())
        .isEqualTo(0.3);
    assertThat(registry.get("recs_feature_writes_total").tag("result", "stale").counter().count())
        .isEqualTo(1);
    assertThat(registry.get("recs_feature_freshness").tag("feature", "item_stat").timer().count())
        .isEqualTo(2);
  }

  @Test
  void userTombstoneDeletesEverythingAndBlocksReplays() throws Exception {
    byte[] lt =
        FeatureEnvelope.encode(
            1, 0, 0, new UserVector("u1", "items_mock_4_v1", new byte[8], "lt", 0));
    listener.onBatch(List.of(rec(Topics.FEATURES_USER, RedisKeys.userLongTerm("u1", "song"), lt)));
    store.put(RedisKeys.userSeed("u1", "song"), new UserVector("u1", "x", new byte[8], "seed", 0));

    listener.onBatch(
        List.of(
            rec(Topics.FEATURES_USER, RedisKeys.userShortTerm("u1", "song"), null),
            rec(Topics.FEATURES_USER, RedisKeys.userLongTerm("u1", "song"), null)));

    assertThat(store.contains(RedisKeys.userLongTerm("u1", "song"))).isFalse();
    assertThat(store.contains(RedisKeys.userSeed("u1", "song")))
        .isFalse(); // written by another service
    listener.onBatch(List.of(rec(Topics.FEATURES_USER, RedisKeys.userLongTerm("u1", "song"), lt)));
    assertThat(store.contains(RedisKeys.userLongTerm("u1", "song"))).isFalse();
  }

  @Test
  void freshnessIsTaggedPerFeatureType() {
    assertThat(FeatureWriterListener.feature("u:{u1}:st:song")).isEqualTo("user_short_term");
    assertThat(FeatureWriterListener.feature("u:{u1}:x")).isEqualTo("user_cross_domain");
    assertThat(FeatureWriterListener.feature("i:{s1}:stat")).isEqualTo("item_stat");
    assertThat(FeatureWriterListener.feature("expl:abc")).isEqualTo("explanation");
  }
}
