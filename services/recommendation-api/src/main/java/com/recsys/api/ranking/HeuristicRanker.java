package com.recsys.api.ranking;

import com.recsys.api.candidates.ScoredCandidate;
import com.recsys.api.core.RecContext;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Weighted sum of the normalized features: semantic similarity, collaborative and session signals,
 * user affinities, popularity, completion rate and freshness, minus a penalty for creators the user
 * just rejected. Weights come from the request's A/B variant.
 */
public final class HeuristicRanker implements Ranker {
  public static final String VERSION = "heuristic-v1";

  @Override
  public String version() {
    return VERSION;
  }

  @Override
  public List<ScoredCandidate> rank(RecContext ctx, Collection<ScoredCandidate> candidates) {
    RankerWeights w =
        ctx.variant().weights() == null ? RankerWeights.defaults() : ctx.variant().weights();
    FeatureExtractor.extract(ctx, candidates);
    for (ScoredCandidate c : candidates) {
      double[] f = c.featureVector;
      double popularity = 0.5 * f[4] + 0.5 * Math.min(1, f[5] / 0.3);
      c.score =
          w.semantic() * f[0]
              + w.cf() * f[1]
              + w.nextItem() * f[2]
              + w.affinity() * f[3]
              + w.popularity() * popularity
              + w.completion() * f[6]
              + w.freshness() * f[7]
              - w.recentNegativePenalty() * f[8];
    }
    ReasonAttribution.apply(ctx, candidates);
    return candidates.stream()
        .sorted(Comparator.comparingDouble((ScoredCandidate c) -> c.score).reversed())
        .toList();
  }
}
