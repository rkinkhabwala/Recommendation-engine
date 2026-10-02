package com.recsys.api.rerank;

import com.recsys.api.candidates.ScoredCandidate;
import com.recsys.api.core.RecContext;
import java.util.List;

/** One step of the business-rule chain applied after ranking. Order matters. */
public interface ReRanker {
  List<ScoredCandidate> apply(RecContext ctx, List<ScoredCandidate> ranked, int limit);
}
