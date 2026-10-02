package com.recsys.api.rerank;

import com.recsys.api.candidates.ScoredCandidate;
import com.recsys.api.core.RecContext;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/** Capped multiplicative boost for recent releases, then re-sort. */
public final class FreshnessBoostReRanker implements ReRanker {

  @Override
  public List<ScoredCandidate> apply(RecContext ctx, List<ScoredCandidate> ranked, int limit) {
    var rules = ctx.rules();
    LocalDate cutoff =
        LocalDate.ofEpochDay((ctx.now() - rules.freshnessBoostWindow().toMillis()) / 86_400_000L);
    for (ScoredCandidate c : ranked) {
      if (c.meta != null && c.meta.releaseDate() != null && c.meta.releaseDate().isAfter(cutoff)) {
        c.score *= 1 + rules.freshnessBoost();
      }
    }
    return ranked.stream()
        .sorted(Comparator.comparingDouble((ScoredCandidate c) -> c.score).reversed())
        .toList();
  }
}
