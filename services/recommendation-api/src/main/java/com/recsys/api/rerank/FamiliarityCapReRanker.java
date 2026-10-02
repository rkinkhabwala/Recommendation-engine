package com.recsys.api.rerank;

import com.recsys.api.candidates.ScoredCandidate;
import com.recsys.api.config.ApiProperties;
import com.recsys.api.core.RecContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** At most {@code familiarityCap} of the list may be tracks the user already liked; extras sink. */
public final class FamiliarityCapReRanker implements ReRanker {
  private final ApiProperties.Rerank props;

  public FamiliarityCapReRanker(ApiProperties.Rerank props) {
    this.props = props;
  }

  @Override
  public List<ScoredCandidate> apply(RecContext ctx, List<ScoredCandidate> ranked, int limit) {
    if (ctx.shortTerm() == null || ctx.shortTerm().liked().isEmpty()) {
      return ranked;
    }
    Set<String> liked = new HashSet<>(ctx.shortTerm().liked());
    int cap = (int) Math.floor(props.familiarityCap() * limit);
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
