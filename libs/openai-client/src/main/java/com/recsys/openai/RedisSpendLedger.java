package com.recsys.openai;

import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import java.time.YearMonth;

/**
 * Shared ledger: {@code INCRBYFLOAT openai:spend:<yyyy-MM>} is atomic across replicas, so the
 * budget guard sees the whole fleet's spend (operational counter, not a feature — it is the one
 * Redis write outside the feature-writer, by design).
 */
public final class RedisSpendLedger implements SpendLedger {
  private static final Duration TTL = Duration.ofDays(40);
  private final StatefulRedisConnection<String, String> redis;

  public RedisSpendLedger(StatefulRedisConnection<String, String> redis) {
    this.redis = redis;
  }

  static String key(YearMonth month) {
    return "openai:spend:" + month;
  }

  @Override
  public double add(YearMonth month, double usd) {
    var sync = redis.sync();
    double total = sync.incrbyfloat(key(month), usd);
    sync.expire(key(month), TTL.toSeconds());
    return total;
  }

  @Override
  public double get(YearMonth month) {
    String v = redis.sync().get(key(month));
    return v == null ? 0 : Double.parseDouble(v);
  }
}
