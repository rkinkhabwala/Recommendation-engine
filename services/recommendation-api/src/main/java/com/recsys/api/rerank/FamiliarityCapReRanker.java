package com.recsys.api.rerank;

import com.recsys.api.candidates.ScoredCandidate;
import com.recsys.api.core.RecContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * At most {@code familiarityCap} of the list may be items the user already liked; extras sink. Only
 * meaningful for replayable domains (songs); a cap of 0 means liked items are filtered.
 */
public final class FamiliarityCapReRanker implements ReRanker {

  @Override
  public List<ScoredCandidate> apply(RecContext ctx, List<ScoredCandidate> ranked, int limit) {
    double share = ctx.rules().familiarityCap();
    if (share <= 0 || ctx.shortTerm() == null || ctx.shortTerm().liked().isEmpty()) {
      return ranked;
    }
    Set<String> liked = new HashSet<>(ctx.shortTerm().liked());
    int cap = (int) Math.floor(share * limit);
    List<ScoredCandidate> head = new ArrayList<>();
    List<ScoredCandidate> tail = new ArrayList<>();
    int familiar = 0;
    for (ScoredCandidate c : ranked) {
      if (liked.contains(c.itemId) && familiar++ >= cap) {
        tail.add(c);
      } else {
        head.add(c);
      }
    }
    head.addAll(tail);
    return head;
  }
}
