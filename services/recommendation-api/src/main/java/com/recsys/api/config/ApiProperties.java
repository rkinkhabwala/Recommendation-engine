package com.recsys.api.config;

import com.recsys.api.ranking.RankerWeights;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Serving configuration. Budgets follow docs/architecture.md §2 (p50 &lt; 30 ms, p99 &lt; 100 ms).
 *
 * @param indexAlias Qdrant alias read by the serving path
 * @param indexVersion embedding space of the current index; user vectors from another space are
 *     ignored
 * @param deadline overall server-side deadline for one request
 */
@ConfigurationProperties("recs.api")
public record ApiProperties(
    String indexAlias,
    String indexVersion,
    List<String> enabledDomains,
    Duration deadline,
    Budgets budgets,
    Candidates candidates,
    Rerank rerank,
    int defaultLimit,
    int maxLimit,
    double featureLogSampleRate,
    Experiments experiments) {

  public record Budgets(
      Duration userFeatures, Duration generators, Duration hydration, Duration rescore) {}

  public record Candidates(
      int ann,
      int fresh,
      Duration freshWindow,
      int cfSeeds,
      int cfPerSeed,
      int nextItem,
      int trending,
      double shortTermBlend) {}

  public record Rerank(
      Duration recentlyPlayedWindow,
      int artistWindow,
      int maxPerArtistPer10,
      double genreDiversityLambda,
      Duration freshnessBoostWindow,
      double freshnessBoost,
      double familiarityCap,
      double explorationEpsilon,
      int explorationMinLimit,
      double lowImpressionThreshold) {}

  public record Experiments(String salt, List<Variant> variants) {}

  /** Bucket range [from, to) out of 1000 and the ranker weights for that variant. */
  public record Variant(String id, int from, int to, RankerWeights weights) {}
}
