package com.recsys.common;

import java.time.Duration;

/** A point in time by which work must finish. Stages take min(own budget, remaining). */
public record Deadline(long expiresAtNanos) {

  public static Deadline in(Duration budget) {
    return new Deadline(System.nanoTime() + budget.toNanos());
  }

  public Duration remaining() {
    return Duration.ofNanos(Math.max(0, expiresAtNanos - System.nanoTime()));
  }

  public boolean expired() {
    return System.nanoTime() >= expiresAtNanos;
  }

  /** Budget for a sub-stage: the smaller of its own budget and what is left overall. */
  public Duration budget(Duration stageBudget) {
    Duration left = remaining();
    return left.compareTo(stageBudget) < 0 ? left : stageBudget;
  }
}
