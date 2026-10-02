package com.recsys.api.ranking;

import com.recsys.api.candidates.ScoredCandidate;
import com.recsys.api.core.RecContext;
import java.util.Collection;
import java.util.List;

/**
 * Scores and orders candidates. Implementations: {@link HeuristicRanker} and {@link LightGbmRanker}
 * (trained offline by ml/recsys_ml/train.py, loaded by {@link RankerRegistry}). Each A/B variant
 * chooses one.
 */
public interface Ranker {
  String version();

  List<ScoredCandidate> rank(RecContext ctx, Collection<ScoredCandidate> candidates);
}
