package com.recsys.api.config;

import com.recsys.api.ranking.RankerWeights;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Serving configuration. Budgets follow docs/architecture.md §2 (p50 &lt; 30 ms, p99 &lt; 100 ms).
 *
 * @param indexAlias Qdrant alias read by the serving path
 * @param indexVersion embedding space of the current index; user vectors from another space are
 *     ignored
 * @param deadline overall server-side deadline for one request
 * @param rerank per-domain business rules ({@code default} applies to unlisted domains)
 * @param experiments per-domain A/B experiments ({@code default} applies to unlisted domains)
 */
@ConfigurationProperties("recs.api")
public record ApiProperties(
    String indexAlias,
    String indexVersion,
    List<String> enabledDomains,
    Duration deadline,
    Budgets budgets,
    Candidates candidates,
    Map<String, Rerank> rerank,
    int defaultLimit,
    int maxLimit,
    double featureLogSampleRate,
    Map<String, Experiments> experiments,
    Ranking ranking) {

  public Rerank rerank(String domain) {
    return rerank.getOrDefault(domain, rerank.get("default"));
  }

  public Experiments experiments(String domain) {
    return experiments.getOrDefault(domain, experiments.get("default"));
  }

  public record Budgets(
      Duration userFeatures,
      Duration generators,
      Duration hydration,
      Duration rescore,
      Duration explanations) {}

  /**
   * @param shortWeight query-vector weight of the domain short-term vector
   * @param longWeight weight of the domain long-term vector
   * @param crossWeight weight of the cross-domain vector (taste from other domains)
   */
  public record Candidates(
      int ann,
      int fresh,
      Duration freshWindow,
      int cfSeeds,
      int cfPerSeed,
      int nextItem,
      int trending,
      double shortWeight,
      double longWeight,
      double crossWeight) {}

  /**
   * @param consumedWindow consumed items are hidden this long (songs replay; books do not)
   * @param artistWindow a creator appears at most once in any window of this many slots
   * @param familiarityCap max share of already-liked items (0 = liked items are filtered)
   * @param explorationStrategy {@code epsilon} or {@code thompson}
   */
  public record Rerank(
      Duration consumedWindow,
      int artistWindow,
      int maxPerArtistPer10,
      double genreDiversityLambda,
      Duration freshnessBoostWindow,
      double freshnessBoost,
      double familiarityCap,
      double explorationEpsilon,
      int explorationMinLimit,
      double lowImpressionThreshold,
      String explorationStrategy) {}

  public record Experiments(String salt, List<Variant> variants) {}

  /**
   * Bucket range [from, to) out of 1000, the ranker ({@code heuristic} or {@code lightgbm}), its
   * weights (heuristic) and an optional exploration strategy override.
   */
  public record Variant(
      String id, int from, int to, String ranker, RankerWeights weights, String exploration) {
    public Variant {
      ranker = ranker == null ? "heuristic" : ranker;
    }
  }

  /**
   * @param modelDir root of the model registry ({@code ranker/<domain>/<version>/})
   */
  public record Ranking(String modelDir, Duration reloadInterval) {}
}
