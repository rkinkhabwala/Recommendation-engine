package com.recsys.api.candidates;

import com.recsys.api.config.ApiProperties;
import com.recsys.api.core.ReasonCode;
import com.recsys.api.core.RecContext;
import com.recsys.features.FeatureReader;
import com.recsys.features.model.Neighbors;
import java.time.Duration;
import java.util.List;

/** Session sequences: what listeners play after the current (or last positively played) item. */
public final class NextItemGenerator implements CandidateGenerator {
  private final FeatureReader features;
  private final ApiProperties props;

  public NextItemGenerator(FeatureReader features, ApiProperties props) {
    this.features = features;
    this.props = props;
  }

  @Override
  public String source() {
    return Sources.NEXT_ITEM;
  }

  @Override
  public List<Candidate> generate(RecContext ctx, Duration timeout) {
    String seed = ctx.request().seedItemId();
    if (seed == null && ctx.shortTerm() != null) {
      seed = ctx.shortTerm().lastItemId();
    }
    if (seed == null) {
      return List.of();
    }
    Neighbors n = features.neighbors(List.of(seed), true, timeout).get(seed);
    if (n == null) {
      return List.of();
    }
    String s = seed;
    return n.items().stream()
        .limit(props.candidates().nextItem())
        .map(
            i ->
                new Candidate(
                    i.itemId(), source(), i.score(), ReasonCode.OFTEN_PLAYED_NEXT, s, null, null))
        .toList();
  }
}
