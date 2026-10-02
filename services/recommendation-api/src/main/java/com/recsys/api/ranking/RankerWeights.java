package com.recsys.api.ranking;

/** Heuristic ranker weights (config, per variant). Positive terms should sum to ~1. */
public record RankerWeights(
    double semantic,
    double cf,
    double nextItem,
    double affinity,
    double popularity,
    double completion,
    double freshness,
    double recentNegativePenalty) {

  public static RankerWeights defaults() {
    return new RankerWeights(0.45, 0.15, 0.10, 0.10, 0.08, 0.07, 0.05, 0.15);
  }
}
