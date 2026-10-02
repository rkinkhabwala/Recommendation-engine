package com.recsys.openai;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Monthly spend guard. Below 100% everything runs; at or above 100% only ESSENTIAL jobs run.
 * Threshold crossings (50%, 80%, 100%) are logged once per month; Prometheus alerts on the ratio.
 *
 * <p>TODO(phase-3): spend is tracked per process. Back it with a shared Redis counter so multiple
 * worker replicas share one budget. The OpenAI project spend limit is the hard backstop.
 */
public final class BudgetGuard {
  private static final Logger log = LoggerFactory.getLogger(BudgetGuard.class);
  private static final double[] THRESHOLDS = {0.5, 0.8, 1.0};

  private final double monthlyBudgetUsd;
  private final Clock clock;
  private YearMonth month;
  private double spentUsd;
  private int thresholdsLogged;

  public BudgetGuard(double monthlyBudgetUsd, Clock clock) {
    this.monthlyBudgetUsd = monthlyBudgetUsd;
    this.clock = clock;
    this.month = YearMonth.now(clock.withZone(ZoneOffset.UTC));
  }

  public synchronized void addSpend(double usd) {
    rollMonth();
    spentUsd += usd;
    double ratio = usedRatioUnsynced();
    while (thresholdsLogged < THRESHOLDS.length && ratio >= THRESHOLDS[thresholdsLogged]) {
      log.warn(
          "OpenAI spend reached {}% of the ${} monthly budget",
          (int) (THRESHOLDS[thresholdsLogged] * 100), monthlyBudgetUsd);
      thresholdsLogged++;
    }
  }

  public synchronized void check(JobPriority priority) {
    rollMonth();
    if (priority == JobPriority.NON_ESSENTIAL && usedRatioUnsynced() >= 1.0) {
      throw new OpenAiException(
          OpenAiException.Kind.BUDGET,
          "Monthly OpenAI budget of $" + monthlyBudgetUsd + " exhausted; non-essential job paused");
    }
  }

  public synchronized double usedRatio() {
    rollMonth();
    return usedRatioUnsynced();
  }

  private double usedRatioUnsynced() {
    return monthlyBudgetUsd <= 0 ? 0 : spentUsd / monthlyBudgetUsd;
  }

  private void rollMonth() {
    YearMonth now = YearMonth.now(clock.withZone(ZoneOffset.UTC));
    if (!now.equals(month)) {
      month = now;
      spentUsd = 0;
      thresholdsLogged = 0;
    }
  }
}
