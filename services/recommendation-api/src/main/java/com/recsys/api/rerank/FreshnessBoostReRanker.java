package com.recsys.api.rerank;

import com.recsys.api.candidates.ScoredCandidate;
import com.recsys.api.config.ApiProperties;
import com.recsys.api.core.RecContext;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/** Capped multiplicative boost for recent releases, then re-sort. */
public final class FreshnessBoostReRanker implements ReRanker {
  private final ApiProperties.Rerank props;

  public FreshnessBoostReRanker(ApiProperties.Rerank props) {
    this.props = props;
  }

  @Override
  public List<ScoredCandidate> apply(RecContext ctx, List<ScoredCandidate> ranked, int limit) {
    LocalDate cutoff =
        LocalDate.ofEpochDay((ctx.now() - props.freshnessBoostWindow().toMillis()) / 86_400_000L);
    for (ScoredCandidate c : ranked) {
      if (c.meta != null && c.meta.releaseDate() != null && c.meta.releaseDate().isAfter(cutoff)) {
        c.score *= 1 + props.freshnessBoost();
      }
    }
    return ranked.stream()
        .sorted(Comparator.comparingDouble((ScoredCandidate c) -> c.score).reversed())
        .toList();
  }
}
