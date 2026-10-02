package com.recsys.api.candidates;

import com.recsys.api.config.ApiProperties;
import com.recsys.api.core.ReasonCode;
import com.recsys.api.core.RecContext;
import com.recsys.features.FeatureReader;
import com.recsys.features.model.Neighbors;
import com.recsys.features.model.RecentInteraction;
import com.recsys.features.model.ScoredItem;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Item-item co-engagement neighbours of the user's most recent positively engaged items. */
public final class ItemItemCfGenerator implements CandidateGenerator {
  private final FeatureReader features;
  private final ApiProperties props;

  public ItemItemCfGenerator(FeatureReader features, ApiProperties props) {
    this.features = features;
    this.props = props;
  }

  @Override
  public String source() {
    return Sources.ITEM_ITEM_CF;
  }

  @Override
  public List<Candidate> generate(RecContext ctx, Duration timeout) {
    LinkedHashSet<String> seeds = new LinkedHashSet<>();
    for (RecentInteraction r : ctx.recent()) {
      if (r.weight() > 0) {
        seeds.add(r.itemId());
      }
      if (seeds.size() >= props.candidates().cfSeeds()) {
        break;
      }
    }
    if (seeds.isEmpty()) {
      return List.of();
    }
    List<String> seedList = List.copyOf(seeds);
    Map<String, Neighbors> lists = features.neighbors(seedList, false, timeout);
    List<Candidate> out = new ArrayList<>();
    for (int i = 0; i < seedList.size(); i++) {
      Neighbors n = lists.get(seedList.get(i));
      if (n == null) {
        continue;
      }
      double recencyWeight = 1.0 - 0.15 * i; // most recent seed counts most
      n.items().stream()
          .limit(props.candidates().cfPerSeed())
          .forEach(
              (ScoredItem s) ->
                  out.add(
                      new Candidate(
                          s.itemId(),
                          source(),
                          s.score() * recencyWeight,
                          ReasonCode.LISTENED_TOGETHER,
                          n.itemId(),
                          null,
                          null)));
    }
    return out;
  }
}
