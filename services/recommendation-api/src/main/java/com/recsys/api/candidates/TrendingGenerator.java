package com.recsys.api.candidates;

import com.recsys.api.config.ApiProperties;
import com.recsys.api.core.ReasonCode;
import com.recsys.api.core.RecContext;
import com.recsys.features.FeatureReader;
import com.recsys.features.model.ScoredItem;
import com.recsys.features.model.TrendingList;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Popular-in-region plus global trending; the always-available fallback source. */
public final class TrendingGenerator implements CandidateGenerator {
  private final FeatureReader features;
  private final ApiProperties props;

  public TrendingGenerator(FeatureReader features, ApiProperties props) {
    this.features = features;
    this.props = props;
  }

  @Override
  public String source() {
    return Sources.TRENDING;
  }

  @Override
  public List<Candidate> generate(RecContext ctx, Duration timeout) {
    String domain = ctx.request().domain();
    List<Candidate> out = new ArrayList<>();
    if (ctx.region() != null) {
      add(out, features.trending(domain, ctx.region(), timeout), ReasonCode.POPULAR_IN_REGION);
    }
    add(out, features.trending(domain, "GLOBAL", timeout), ReasonCode.TRENDING);
    return out;
  }

  private void add(List<Candidate> out, TrendingList list, ReasonCode reason) {
    if (list == null || list.items().isEmpty()) {
      return;
    }
    double max = list.items().get(0).score();
    for (ScoredItem s : list.items().stream().limit(props.candidates().trending()).toList()) {
      out.add(
          new Candidate(
              s.itemId(), source(), max <= 0 ? 0 : s.score() / max, reason, null, null, null));
    }
  }
}
