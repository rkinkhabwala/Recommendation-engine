package com.recsys.api.ranking;

import com.recsys.api.candidates.ScoredCandidate;
import com.recsys.api.core.RecContext;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Learned ranker (LightGBM LambdaRank) over the same features as the heuristic. The model was
 * trained with the served position as an extra feature to absorb position bias; at inference
 * position is fixed to 0 so items are compared as if shown in the same slot.
 */
public final class LightGbmRanker implements Ranker {
  private final LightGbmModel model;
  private final String version;

  public LightGbmRanker(LightGbmModel model, String modelVersion) {
    this.model = model;
    this.version = "lgbm-" + modelVersion;
  }

  @Override
  public String version() {
    return version;
  }

  @Override
  public List<ScoredCandidate> rank(RecContext ctx, Collection<ScoredCandidate> candidates) {
    FeatureExtractor.extract(ctx, candidates);
    int n = FeatureExtractor.FEATURES.size();
    double[] x = new double[n + 1]; // + position (0 at inference)
    for (ScoredCandidate c : candidates) {
      System.arraycopy(c.featureVector, 0, x, 0, n);
      c.score = model.predict(x);
    }
    ReasonAttribution.apply(ctx, candidates);
    return candidates.stream()
        .sorted(Comparator.comparingDouble((ScoredCandidate c) -> c.score).reversed())
        .toList();
  }
}
