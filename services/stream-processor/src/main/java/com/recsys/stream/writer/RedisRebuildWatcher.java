package com.recsys.stream.writer;

import io.lettuce.core.api.StatefulRedisConnection;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Redis is a materialized view of the compacted {@code features.*} topics. If Redis loses its data
 * (restart without persistence, failover to an empty replica, FLUSHALL), the sentinel key
 * disappears and the feature-writer rewinds to the beginning of its topics and replays them.
 * Compare-and-set on seq makes the replay idempotent; tombstones re-apply deletions.
 */
@Component
public class RedisRebuildWatcher {
  static final String SENTINEL = "recs:feature-writer:sentinel";
  private static final Logger log = LoggerFactory.getLogger(RedisRebuildWatcher.class);

  private final StatefulRedisConnection<String, byte[]> redis;
  private final FeatureWriterListener listener;

  public RedisRebuildWatcher(
      StatefulRedisConnection<String, byte[]> redis, FeatureWriterListener listener) {
    this.redis = redis;
    this.listener = listener;
  }

  @Scheduled(
      fixedDelayString = "${recs.writer.rebuild-check-interval:10s}",
      initialDelayString = "15s")
  public void check() {
    try {
      if (redis.sync().exists(SENTINEL) == 0) {
        log.warn("Redis sentinel missing: replaying compacted feature topics to rebuild Redis");
        listener.rewindAll();
        redis
            .sync()
            .set(
                SENTINEL,
                Long.toString(System.currentTimeMillis()).getBytes(StandardCharsets.US_ASCII));
      }
    } catch (RuntimeException e) {
      log.debug("Rebuild check skipped (Redis unavailable): {}", e.toString());
    }
  }
}
