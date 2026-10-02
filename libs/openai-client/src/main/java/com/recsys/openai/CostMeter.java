package com.recsys.openai;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Token and cost accounting. Emits {@code recs_openai_tokens_total} and {@code
 * recs_openai_cost_usd_total} per model/job, plus {@code recs_openai_budget_used_ratio} which the
 * Prometheus alerts (50% / 80%) watch.
 */
public final class CostMeter {
  private static final Logger log = LoggerFactory.getLogger(CostMeter.class);

  private final MeterRegistry registry;
  private final Map<String, Double> pricePerMillion;
  private final BudgetGuard budget;

  public CostMeter(
      MeterRegistry registry, Map<String, Double> pricePerMillion, BudgetGuard budget) {
    this.registry = registry;
    this.pricePerMillion = pricePerMillion;
    this.budget = budget;
    Gauge.builder("recs_openai_budget_used_ratio", budget, BudgetGuard::usedRatio)
        .description("Fraction of the monthly OpenAI budget spent (this process)")
        .register(registry);
  }

  public void record(String model, String job, long tokens) {
    double price = pricePerMillion.getOrDefault(model, 0.0);
    if (price == 0.0 && !model.startsWith("mock")) {
      log.warn("No price configured for model {}; cost metrics will read 0", model);
    }
    double usd = tokens / 1_000_000.0 * price;
    Counter.builder("recs_openai_tokens_total")
        .tag("model", model)
        .tag("job", job)
        .register(registry)
        .increment(tokens);
    Counter.builder("recs_openai_cost_usd_total")
        .tag("model", model)
        .tag("job", job)
        .register(registry)
        .increment(usd);
    budget.addSpend(usd);
  }

  public BudgetGuard budget() {
    return budget;
  }
}
