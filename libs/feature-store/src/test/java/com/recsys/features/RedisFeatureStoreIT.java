package com.recsys.features;

import static org.assertj.core.api.Assertions.assertThat;

import com.recsys.features.model.ItemStats;
import com.recsys.features.model.UserVector;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class RedisFeatureStoreIT {
  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

  static RedisClient client;
  static StatefulRedisConnection<String, byte[]> conn;
  static RedisFeatureStore store;
  static final Duration T = Duration.ofSeconds(2);

  @BeforeAll
  static void setUp() {
    client = RedisClient.create();
    conn =
        RedisConnections.connect(
            client, "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379), T);
    store = new RedisFeatureStore(conn);
  }

  @AfterAll
  static void tearDown() {
    conn.close();
    client.shutdown();
  }

  static byte[] stats(String id, double ctr) {
    return FeatureJson.write(new ItemStats(id, 10, 2, ctr, 0.5, 0.1, 1, 1, 0));
  }

  @Test
  void replayedOrOlderSeqCannotRegressState() throws Exception {
    String key = RedisKeys.itemStats("s_cas");
    assertThat(store.upsert(key, 5, stats("s_cas", 0.5), 0).get()).isEqualTo(WriteResult.APPLIED);
    assertThat(store.upsert(key, 5, stats("s_cas", 0.9), 0).get()).isEqualTo(WriteResult.STALE);
    assertThat(store.upsert(key, 3, stats("s_cas", 0.1), 0).get()).isEqualTo(WriteResult.STALE);
    assertThat(store.itemStats(List.of("s_cas"), T).get("s_cas").ctr()).isEqualTo(0.5);
    assertThat(store.upsert(key, 6, stats("s_cas", 0.7), 60).get()).isEqualTo(WriteResult.APPLIED);
    assertThat(store.itemStats(List.of("s_cas"), T).get("s_cas").ctr()).isEqualTo(0.7);
    assertThat(conn.sync().ttl(key)).isBetween(1L, 60L);
  }

  @Test
  void deletedUserCannotBeResurrectedByReplay() throws Exception {
    String uid = "u_del";
    byte[] vec = FeatureJson.write(new UserVector(uid, "items_mock_4_v1", new byte[8], "lt", 1));
    store.upsert(RedisKeys.userLongTerm(uid), 1, vec, 0).get();
    store.upsert(RedisKeys.userSeed(uid), 1, vec, 0).get();
    assertThat(store.user(uid, T).longTerm()).isNotNull();

    store.deleteUser(uid, Duration.ofDays(30)).get();

    assertThat(store.user(uid, T).isEmpty()).isTrue();
    assertThat(conn.sync().exists(RedisKeys.userLongTerm(uid), RedisKeys.userSeed(uid))).isZero();
    // A lagging replay of an old feature record must be refused.
    assertThat(store.upsert(RedisKeys.userLongTerm(uid), 99, vec, 0).get())
        .isEqualTo(WriteResult.USER_DELETED);
    assertThat(store.user(uid, T).isEmpty()).isTrue();
  }

  @Test
  void survivesScriptCacheFlush() throws Exception {
    String key = RedisKeys.itemStats("s_flush");
    store.upsert(key, 1, stats("s_flush", 0.2), 0).get();
    conn.sync().scriptFlush();
    assertThat(store.upsert(key, 2, stats("s_flush", 0.3), 0).get()).isEqualTo(WriteResult.APPLIED);
  }

  @Test
  void pipelinedMultiReadSkipsMissing() {
    store.upsert(RedisKeys.itemStats("s_a"), 1, stats("s_a", 0.1), 0).join();
    assertThat(store.itemStats(List.of("s_a", "s_missing"), T)).containsOnlyKeys("s_a");
  }
}
