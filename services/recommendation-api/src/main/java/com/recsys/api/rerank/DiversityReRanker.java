package com.recsys.api.rerank;

import com.recsys.api.candidates.ScoredCandidate;
import com.recsys.api.config.ApiProperties;
import com.recsys.api.core.RecContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Greedy diversified selection: an artist may appear at most once in any window of {@code
 * artistWindow} consecutive slots and at most {@code maxPerArtistPer10} times per 10 slots (a
 * strict superset of "no 5 songs by the same artist in a row"). Genre repetition is softly
 * penalized (MMR-style, λ). If no candidate satisfies the artist rules the best one is taken, so
 * the list is never shorter than the pool allows.
 */
public final class DiversityReRanker implements ReRanker {
  private static final int LOOKAHEAD = 50;
  private final ApiProperties.Rerank props;

  public DiversityReRanker(ApiProperties.Rerank props) {
    this.props = props;
  }

  @Override
  public List<ScoredCandidate> apply(RecContext ctx, List<ScoredCandidate> ranked, int limit) {
    LinkedList<ScoredCandidate> remaining = new LinkedList<>(ranked);
    List<ScoredCandidate> out = new ArrayList<>();
    Map<String, Integer> genreCounts = new HashMap<>();
    int target = Math.min(limit + LOOKAHEAD / 5, ranked.size());
    while (out.size() < target && !remaining.isEmpty()) {
      ScoredCandidate best = null;
      double bestScore = Double.NEGATIVE_INFINITY;
      int scanned = 0;
      for (ScoredCandidate c : remaining) {
        if (scanned++ >= LOOKAHEAD) {
          break;
        }
        if (!artistAllowed(c, out)) {
          continue;
        }
        int sameGenre =
            c.primaryGenre() == null ? 0 : genreCounts.getOrDefault(c.primaryGenre(), 0);
        double penalty =
            out.isEmpty() ? 0 : (1 - props.genreDiversityLambda()) * sameGenre / out.size();
        double adjusted = c.score - penalty;
        if (adjusted > bestScore) {
          bestScore = adjusted;
          best = c;
        }
      }
      if (best == null) {
        best = remaining.getFirst(); // relax rather than return a short list
      }
      remaining.remove(best);
      out.add(best);
      if (best.primaryGenre() != null) {
        genreCounts.merge(best.primaryGenre(), 1, Integer::sum);
      }
    }
    out.addAll(remaining);
    return out;
  }

  private boolean artistAllowed(ScoredCandidate c, List<ScoredCandidate> out) {
    String artist = c.artistId();
    if (artist == null) {
      return true;
    }
    int n = out.size();
    for (int i = Math.max(0, n - (props.artistWindow() - 1)); i < n; i++) {
      if (Objects.equals(out.get(i).artistId(), artist)) {
        return false;
      }
    }
    int windowStart = (n / 10) * 10;
    long inBlock =
        out.subList(windowStart, n).stream().filter(o -> artist.equals(o.artistId())).count();
    return inBlock < props.maxPerArtistPer10();
  }
}
