package com.recsys.api.candidates;

import com.recsys.api.core.RecContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs all generators in parallel (virtual threads) under one deadline, then merges and dedupes by
 * item id. A slow or failing generator only removes its own candidates.
 */
public final class CandidateService {
  private static final Logger log = LoggerFactory.getLogger(CandidateService.class);

  public record Result(Map<String, ScoredCandidate> pool, boolean partial) {}

  private final List<CandidateGenerator> generators;
  private final ExecutorService executor;
  private final MeterRegistry metrics;

  public CandidateService(
      List<CandidateGenerator> generators, ExecutorService executor, MeterRegistry metrics) {
    this.generators = generators;
    this.executor = executor;
    this.metrics = metrics;
  }

  public Result generate(RecContext ctx, Duration budget) {
    long deadline = System.nanoTime() + budget.toNanos();
    Map<CandidateGenerator, CompletableFuture<List<Candidate>>> futures = new LinkedHashMap<>();
    for (CandidateGenerator g : generators) {
      futures.put(g, CompletableFuture.supplyAsync(() -> g.generate(ctx, budget), executor));
    }
    Map<String, ScoredCandidate> pool = new LinkedHashMap<>();
    boolean partial = false;
    for (var e : futures.entrySet()) {
      String source = e.getKey().source();
      List<Candidate> candidates = List.of();
      String result = "ok";
      try {
        long left = Math.max(0, deadline - System.nanoTime());
        candidates = e.getValue().get(left, TimeUnit.NANOSECONDS);
      } catch (java.util.concurrent.TimeoutException ex) {
        e.getValue().cancel(true);
        result = "timeout";
        partial = true;
      } catch (Exception ex) {
        result = "error";
        partial = true;
        log.debug("Generator {} failed: {}", source, ex.toString());
      }
      metrics.counter("recs_api_generator_total", "source", source, "result", result).increment();
      for (Candidate c : candidates) {
        pool.computeIfAbsent(c.itemId(), ScoredCandidate::new).absorb(c);
      }
    }
    return new Result(pool, partial);
  }

  public List<String> sources() {
    List<String> s = new ArrayList<>();
    generators.forEach(g -> s.add(g.source()));
    return s;
  }
}
