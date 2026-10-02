package com.recsys.common;

/** Exponential half-life decay. */
public final class Decay {
  private Decay() {}

  /** Multiplier for a value observed {@code ageMs} ago: 2^(-age / halfLife). */
  public static double factor(long ageMs, long halfLifeMs) {
    if (ageMs <= 0) {
      return 1.0;
    }
    return Math.pow(2.0, -(double) ageMs / halfLifeMs);
  }
}
