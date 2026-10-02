package com.recsys.api.candidates;

import com.recsys.api.config.ApiProperties;
import com.recsys.api.core.RecContext;
import com.recsys.vector.SearchFilter;
import com.recsys.vector.VectorIndex;
import java.time.Duration;
import java.util.List;

/** ANN over item embeddings with the user's blended real-time vector (or onboarding seed). */
public final class SemanticAnnGenerator implements CandidateGenerator {
  private final VectorIndex index;
  private final ApiProperties props;

  public SemanticAnnGenerator(VectorIndex index, ApiProperties props) {
    this.index = index;
    this.props = props;
  }

  @Override
  public String source() {
    return Sources.SEMANTIC_ANN;
  }

  @Override
  public List<Candidate> generate(RecContext ctx, Duration timeout) {
    if (ctx.queryVector() == null) {
      return List.of();
    }
    var r = ctx.request();
    var filter = new SearchFilter(r.domain(), r.country(), r.explicitAllowed(), null, List.of());
    return index
        .search(props.indexAlias(), ctx.queryVector(), filter, props.candidates().ann(), timeout)
        .stream()
        .map(
            h ->
                new Candidate(
                    h.itemId(),
                    source(),
                    Math.max(0, h.score()),
                    ctx.semanticReason(),
                    null,
                    h.score(),
                    h.payload()))
        .toList();
  }
}
