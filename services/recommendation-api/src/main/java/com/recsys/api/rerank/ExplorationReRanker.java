package com.recsys.api.rerank;

import com.recsys.api.candidates.ScoredCandidate;
import com.recsys.api.candidates.Sources;
import com.recsys.api.config.ApiProperties;
import com.recsys.api.core.ReasonCode;
import com.recsys.api.core.RecContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Epsilon-greedy exploration: ~ε of slots go to new or under-exposed items outside the top list
 * (never slot 0 on next_track). Each explored item records its selection propensity so offline
 * evaluation can correct for it (IPS). TODO(phase-2): Thompson sampling on per-item Beta(play,
 * skip).
 */
public final class ExplorationReRanker implements ReRanker {
  private final ApiProperties.Rerank props;

  public ExplorationReRanker(ApiProperties.Rerank props) {
    this.props = props;
  }

  @Override
  public List<ScoredCandidate> apply(RecContext ctx, List<ScoredCandidate> ranked, int limit) {
    if (limit < props.explorationMinLimit() || ranked.size() <= limit) {
      return ranked;
    }
    int slots = Math.max(1, (int) Math.round(props.explorationEpsilon() * limit));
    List<ScoredCandidate> head = new ArrayList<>(ranked.subList(0, limit));
    Set<String> artistsInHead = new HashSet<>();
    head.forEach(c -> artistsInHead.add(c.artistId()));
    List<ScoredCandidate> pool =
        ranked.subList(limit, ranked.size()).stream()
            // Explore only with evidence: a new item, or one with known low exposure. Missing
            // stats alone (e.g. Redis down, popular fallback) is not evidence.
            .filter(
                c ->
                    c.source(Sources.FRESH) > 0
                        || (c.stats != null
                            && c.stats.impressions() < props.lowImpressionThreshold()))
            .toList();
    // Prefer artists not already in the list (more novelty); fall back to the whole pool.
    List<ScoredCandidate> novel =
        pool.stream().filter(c -> !artistsInHead.contains(c.artistId())).toList();
    if (!novel.isEmpty()) {
      pool = novel;
    }
    if (pool.isEmpty()) {
      return ranked;
    }
    // Seeded from user + time so explored items and slots vary across requests.
    Random random = new Random((ctx.request().userId() + ctx.now()).hashCode());
    List<ScoredCandidate> poolCopy = new ArrayList<>(pool);
    int minPos = ctx.request().nextTrack() ? 1 : 2;
    int take = Math.min(slots, poolCopy.size());
    double propensity = (double) take / pool.size();
    for (int i = 0; i < take; i++) {
      ScoredCandidate pick = poolCopy.remove(random.nextInt(poolCopy.size()));
      pick.explore = true;
      pick.propensity = propensity;
      pick.reason = ReasonCode.NEW_FOR_YOU;
      pick.seedItemId = null;
      int pos = minPos + random.nextInt(Math.max(1, limit - minPos));
      head.remove(head.size() - 1); // drop the weakest exploit item
      head.add(Math.min(pos, head.size()), pick);
    }
    return head;
  }
}
