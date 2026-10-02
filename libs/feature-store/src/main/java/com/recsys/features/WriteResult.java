package com.recsys.features;

/** Outcome of a compare-and-set write. */
public enum WriteResult {
  APPLIED,
  /** Redis already holds an equal or newer seq (replay/duplicate). */
  STALE,
  /** The user was deleted; writes are refused so replays cannot resurrect data. */
  USER_DELETED
}
