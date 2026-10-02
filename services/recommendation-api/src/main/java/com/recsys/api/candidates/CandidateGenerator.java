package com.recsys.api.candidates;

import com.recsys.api.core.RecContext;
import java.time.Duration;
import java.util.List;

/** A candidate source. Implementations must be fast, side-effect free and respect the timeout. */
public interface CandidateGenerator {
  String source();

  List<Candidate> generate(RecContext ctx, Duration timeout);
}
