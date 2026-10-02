package com.recsys.api.candidates;

import com.recsys.api.config.ApiProperties;
import com.recsys.api.core.ReasonCode;
import com.recsys.api.core.RecContext;
import com.recsys.vector.SearchFilter;
import com.recsys.vector.VectorIndex;
import java.time.Duration;
import java.util.List;

/**
 * New-item cold start: items ingested within the fresh window that are close to the user's taste.
 * New items are embedded on ingest, so they are retrievable within seconds; this source also feeds
 * the exploration pool.
 */
public final class FreshItemsGenerator implements CandidateGenerator {
  private final VectorIndex index;
  private final ApiProperties props;

  public FreshItemsGenerator(VectorIndex index, ApiProperties props) {
    this.index = index;
    this.props = props;
  }

  @Override
  public String source() {
    return Sources.FRESH;
  }

  @Override
  public List<Candidate> generate(RecContext ctx, Duration timeout) {
    if (ctx.queryVector() == null) {
      return List.of();
    }
    var r = ctx.request();
    long after = ctx.now() - props.candidates().freshWindow().toMillis();
    var filter = new SearchFilter(r.domain(), r.country(), r.explicitAllowed(), after, List.of());
    return index
        .search(props.indexAlias(), ctx.queryVector(), filter, props.candidates().fresh(), timeout)
        .stream()
        .map(
            h ->
                new Candidate(
                    h.itemId(),
                    source(),
                    Math.max(0, h.score()),
                    ReasonCode.NEW_FOR_YOU,
                    null,
                    h.score(),
                    h.payload()))
        .toList();
  }
}
