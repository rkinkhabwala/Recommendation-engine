package com.recsys.api.ranking;

import com.recsys.api.candidates.ScoredCandidate;
import com.recsys.api.core.RecContext;
import java.util.Collection;
import java.util.List;

/**
 * Scores and orders candidates. {@link HeuristicRanker} today; TODO(phase-2): LightGbmRanker behind
 * the same interface, trained on recs.attributed.v1 joined with logged features.
 */
public interface Ranker {
  String version();

  List<ScoredCandidate> rank(RecContext ctx, Collection<ScoredCandidate> candidates);
}
