package com.recsys.api.candidates;

import com.recsys.api.core.RecContext;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.util.List;

/**
 * Wraps a generator with the circuit breaker of the store it reads (Redis or Qdrant), so a
 * dependency that is down fails instantly instead of every request waiting out its timeout.
 */
public final class GuardedGenerator implements CandidateGenerator {
  private final CandidateGenerator delegate;
  private final CircuitBreaker breaker;

  public GuardedGenerator(CandidateGenerator delegate, CircuitBreaker breaker) {
    this.delegate = delegate;
    this.breaker = breaker;
  }

  @Override
  public String source() {
    return delegate.source();
  }

  @Override
  public List<Candidate> generate(RecContext ctx, Duration timeout) {
    return breaker.executeSupplier(() -> delegate.generate(ctx, timeout));
  }
}
