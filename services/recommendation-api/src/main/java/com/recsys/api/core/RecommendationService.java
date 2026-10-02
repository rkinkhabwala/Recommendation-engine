package com.recsys.api.core;

import com.recsys.api.candidates.Candidate;
import com.recsys.api.candidates.CandidateService;
import com.recsys.api.candidates.ScoredCandidate;
import com.recsys.api.candidates.Sources;
import com.recsys.api.config.ApiProperties;
import com.recsys.api.config.ApiProperties.Variant;
import com.recsys.api.experiment.Experiments;
import com.recsys.api.fallback.PopularCache;
import com.recsys.api.hydration.Hydrator;
import com.recsys.api.logging.ServedLogger;
import com.recsys.api.ranking.Ranker;
import com.recsys.api.ranking.RankerRegistry;
import com.recsys.api.rerank.ReRanker;
import com.recsys.common.Deadline;
import com.recsys.common.Ids;
import com.recsys.events.v1.Domain;
import com.recsys.events.v1.RecommendationServed;
import com.recsys.events.v1.ServedItem;
import com.recsys.features.FeatureReader;
import com.recsys.features.RedisKeys;
import com.recsys.features.model.Explanation;
import com.recsys.features.model.UserFeatures;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * The hot path: user features → parallel candidate generation → hydration → ranking (heuristic or
 * learned, per A/B variant) → re-ranking → fallback/top-up → cached explanations → async served
 * log. Reads only precomputed data (Redis, Qdrant, in-process caches, model files); it never calls
 * OpenAI and never throws for a dependency failure — it degrades and reports {@link FallbackLevel}.
 */
public final class RecommendationService {
  private final FeatureReader features;
  private final CandidateService candidates;
  private final Hydrator hydrator;
  private final RankerRegistry rankers;
  private final List<ReRanker> reRankers;
  private final PopularCache popular;
  private final ServedLogger servedLogger;
  private final Experiments experiments;
  private final ApiProperties props;
  private final CircuitBreaker redisBreaker;
  private final MeterRegistry metrics;
  private final Clock clock;

  public RecommendationService(
      FeatureReader features,
      CandidateService candidates,
      Hydrator hydrator,
      RankerRegistry rankers,
      List<ReRanker> reRankers,
      PopularCache popular,
      ServedLogger servedLogger,
      Experiments experiments,
      ApiProperties props,
      CircuitBreaker redisBreaker,
      MeterRegistry metrics,
      Clock clock) {
    this.features = features;
    this.candidates = candidates;
    this.hydrator = hydrator;
    this.rankers = rankers;
    this.reRankers = reRankers;
    this.popular = popular;
    this.servedLogger = servedLogger;
    this.experiments = experiments;
    this.props = props;
    this.redisBreaker = redisBreaker;
    this.metrics = metrics;
    this.clock = clock;
  }

  public RecommendationResult recommend(RecRequest req) {
    Timer.Sample total = Timer.start(metrics);
    Deadline deadline = Deadline.in(props.deadline());
    var budgets = props.budgets();
    String domain = req.domain();
    Variant variant = experiments.variant(domain, req.userId());
    FallbackLevel level = FallbackLevel.NONE;
    long now = clock.millis();

    UserFeatures uf;
    try {
      uf =
          stage(
              "user_features",
              () ->
                  redisBreaker.executeSupplier(
                      () ->
                          features.user(
                              req.userId(), domain, deadline.budget(budgets.userFeatures()))));
    } catch (RuntimeException e) {
      uf = UserFeatures.EMPTY;
      level = FallbackLevel.ANONYMOUS;
    }
    // Only sampled requests log ranking features; skipping the rest saves ~500 maps per request.
    boolean logFeatures = ThreadLocalRandom.current().nextDouble() < props.featureLogSampleRate();
    RecContext ctx =
        RecContext.of(
            req,
            variant,
            props.rerank(domain),
            uf,
            props.indexVersion(),
            props.candidates(),
            now,
            deadline,
            logFeatures);

    var generated =
        stage("candidates", () -> candidates.generate(ctx, deadline.budget(budgets.generators())));
    Map<String, ScoredCandidate> pool = generated.pool();
    if (generated.partial()) {
      level = level.worst(FallbackLevel.PARTIAL);
    }
    if (pool.isEmpty()) {
      var p = popular.get(domain, req.country());
      level = level.worst(p.fromStatic() ? FallbackLevel.STATIC : FallbackLevel.CACHED_POPULAR);
      pool = popularPool(p.items(), Set.of());
    }

    Map<String, ScoredCandidate> finalPool = pool;
    if (!stage(
        "hydration",
        () -> hydrator.hydrate(finalPool.values(), deadline.budget(budgets.hydration())))) {
      level = level.worst(FallbackLevel.PARTIAL);
    }
    if (!stage(
        "rescore",
        () -> hydrator.rescore(ctx, finalPool.values(), deadline.budget(budgets.rescore())))) {
      level = level.worst(FallbackLevel.PARTIAL);
    }

    Ranker ranker = rankers.forVariant(domain, variant);
    List<ScoredCandidate> ranked =
        stage("rank", () -> rerank(ctx, ranker.rank(ctx, finalPool.values()), req.limit()));
    List<ScoredCandidate> items =
        new ArrayList<>(ranked.subList(0, Math.min(req.limit(), ranked.size())));
    if (items.size() < req.limit()) {
      topUp(ctx, items, req.limit());
    }
    if (level != FallbackLevel.CACHED_POPULAR && level != FallbackLevel.STATIC) {
      stage(
          "explanations",
          () -> attachExplanations(domain, items, deadline.budget(budgets.explanations())));
    }

    String recId = Ids.newId();
    var result =
        new RecommendationResult(
            recId,
            variant.id(),
            ranker.version(),
            props.indexVersion(),
            level,
            Instant.ofEpochMilli(now),
            items);
    if (servedLogger != null) {
      servedLogger.log(toServed(req, result, ctx.logFeatures()));
    }
    metrics.counter("recs_api_fallback_total", "domain", domain, "level", level.name()).increment();
    total.stop(
        Timer.builder("recs_api_request_seconds")
            .tag("domain", domain)
            .tag("fallback", level.name())
            .publishPercentileHistogram()
            .serviceLevelObjectives(Duration.ofMillis(30), Duration.ofMillis(100))
            .register(metrics));
    return result;
  }

  /** Read-only lookup of cached LLM explanations; a miss leaves the client-side template. */
  private boolean attachExplanations(String domain, List<ScoredCandidate> items, Duration budget) {
    Map<String, ScoredCandidate> byKey = new LinkedHashMap<>();
    for (ScoredCandidate c : items) {
      if (c.reason != null && c.reason != ReasonCode.POPULAR_FALLBACK) {
        byKey.put(RedisKeys.explanation(domain, c.reason.name(), c.seedItemId, c.itemId), c);
      }
    }
    if (byKey.isEmpty()) {
      return true;
    }
    try {
      Map<String, Explanation> found = features.explanations(byKey.keySet(), budget);
      found.forEach((k, e) -> byKey.get(k).explanation = e.text());
      return true;
    } catch (RuntimeException e) {
      return false; // explanations are optional; never degrade the response for them
    }
  }

  private List<ScoredCandidate> rerank(RecContext ctx, List<ScoredCandidate> ranked, int limit) {
    List<ScoredCandidate> list = ranked;
    for (ReRanker r : reRankers) {
      list = r.apply(ctx, list, limit);
    }
    return list;
  }

  /** Fill short lists (sparse catalog / heavy filtering) from popular items. */
  private void topUp(RecContext ctx, List<ScoredCandidate> items, int limit) {
    Set<String> exclude = new HashSet<>();
    items.forEach(i -> exclude.add(i.itemId));
    var st = ctx.shortTerm();
    if (st != null) {
      exclude.addAll(st.consumed().keySet());
      exclude.addAll(st.suppressedItems());
    }
    if (ctx.request().seedItemId() != null) {
      exclude.add(ctx.request().seedItemId());
    }
    for (ScoredCandidate c :
        popularPool(popular.get(ctx.domain(), ctx.region()).items(), exclude).values()) {
      if (items.size() >= limit) {
        break;
      }
      items.add(c);
    }
  }

  private static Map<String, ScoredCandidate> popularPool(List<String> ids, Set<String> exclude) {
    Map<String, ScoredCandidate> pool = new LinkedHashMap<>();
    int n = ids.size();
    for (int i = 0; i < n; i++) {
      String id = ids.get(i);
      if (exclude.contains(id)) {
        continue;
      }
      var c = new ScoredCandidate(id);
      c.absorb(
          new Candidate(
              id,
              Sources.POPULAR_FALLBACK,
              1.0 - (double) i / Math.max(1, n),
              ReasonCode.POPULAR_FALLBACK,
              null,
              null,
              null));
      pool.put(id, c);
    }
    return pool;
  }

  private RecommendationServed toServed(
      RecRequest req, RecommendationResult r, boolean logFeatures) {
    List<ServedItem> items = new ArrayList<>();
    for (int i = 0; i < r.items().size(); i++) {
      ScoredCandidate c = r.items().get(i);
      items.add(
          ServedItem.newBuilder()
              .setItemId(c.itemId)
              .setPosition(i)
              .setScore(c.score)
              .setSources(List.copyOf(c.sourceScores.keySet()))
              .setReasonCode(
                  c.reason == null ? ReasonCode.POPULAR_FALLBACK.name() : c.reason.name())
              .setSeedItemId(c.seedItemId)
              .setExplore(c.explore)
              .setPropensity(c.propensity)
              .setFeatures(logFeatures && !c.features.isEmpty() ? Map.copyOf(c.features) : null)
              .build());
    }
    return RecommendationServed.newBuilder()
        .setRecommendationId(r.recommendationId())
        .setUserId(req.userId())
        .setDomain(Domain.valueOf(req.domain().toUpperCase(Locale.ROOT)))
        .setSurface(req.surface())
        .setVariantId(r.variantId())
        .setRankerVersion(r.rankerVersion())
        .setIndexVersion(r.indexVersion())
        .setFallbackLevel(r.fallbackLevel().name())
        .setServedTs(r.generatedAt())
        .setItems(items)
        .build();
  }

  private <T> T stage(String name, Supplier<T> work) {
    Timer.Sample s = Timer.start(metrics);
    try {
      return work.get();
    } finally {
      s.stop(metrics.timer("recs_api_stage_seconds", "stage", name));
    }
  }
}
