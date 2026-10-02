package com.recsys.ingestion.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;

/**
 * Per-user token bucket (bot / runaway-client guard). In-process: with N ingestion replicas the
 * effective limit is N× the configured rate, which is acceptable for abuse protection.
 * TODO(phase-3): enforce at the gateway.
 */
public final class UserRateLimiter {
  private static final class Bucket {
    double tokens;
    long lastNanos;
  }

  private final double ratePerSecond;
  private final int burst;
  private final Cache<String, Bucket> buckets =
      Caffeine.newBuilder()
          .expireAfterAccess(Duration.ofMinutes(10))
          .maximumSize(1_000_000)
          .build();

  public UserRateLimiter(double ratePerSecond, int burst) {
    this.ratePerSecond = ratePerSecond;
    this.burst = burst;
  }

  public boolean tryAcquire(String userId) {
    Bucket b =
        buckets.get(
            userId,
            k -> {
              Bucket nb = new Bucket();
              nb.tokens = burst;
              nb.lastNanos = System.nanoTime();
              return nb;
            });
    synchronized (b) {
      long now = System.nanoTime();
      b.tokens = Math.min(burst, b.tokens + (now - b.lastNanos) / 1e9 * ratePerSecond);
      b.lastNanos = now;
      if (b.tokens >= 1) {
        b.tokens -= 1;
        return true;
      }
      return false;
    }
  }
}
