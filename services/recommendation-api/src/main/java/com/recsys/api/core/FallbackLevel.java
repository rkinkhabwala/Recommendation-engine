package com.recsys.api.core;

/** How degraded a response is; reported on every response and served record. */
public enum FallbackLevel {
  NONE,
  /** At least one generator / hydration / rescore step failed or timed out. */
  PARTIAL,
  /** User features unavailable: trending + popular-in-region only. */
  ANONYMOUS,
  /** Redis unavailable: in-process popular lists. */
  CACHED_POPULAR,
  /** Nothing else available: list bundled with the service. */
  STATIC;

  public FallbackLevel worst(FallbackLevel other) {
    return other.ordinal() > ordinal() ? other : this;
  }
}
