package com.recsys.openai;

import java.time.Clock;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Monthly spend guard over a {@link SpendLedger} (Redis-backed in production, so every replica
 * shares one budget). Below 100% everything runs; at or above 100% only ESSENTIAL jobs run.
 * Threshold crossings (50%, 80%, 100%) are logged once per month per process; Prometheus alerts on
 * the ratio. If the ledger is unreachable, spend is still counted locally and the guard keeps
 * working on the last known total (fail open for essential, conservative for the rest). The OpenAI
 * project spend limit remains the hard backstop.
 */
public final class BudgetGuard {
  private static final Logger log = LoggerFactory.getLogger(BudgetGuard.class);
  private static final double[] THRESHOLDS = {0.5, 0.8, 1.0};
  private static final Duration REFRESH = Duration.ofSeconds(30);

  private final double monthlyBudgetUsd;
  private final Clock clock;
  private final SpendLedger ledger;
  private YearMonth month;
  private double knownTotal;
  private long refreshedAtMs;
  private int thresholdsLogged;

  public BudgetGuard(double monthlyBudgetUsd, Clock clock) {
    this(monthlyBudgetUsd, clock, new SpendLedger.InMemory());
  }

  public BudgetGuard(double monthlyBudgetUsd, Clock clock, SpendLedger ledger) {
    this.monthlyBudgetUsd = monthlyBudgetUsd;
    this.clock = clock;
    this.ledger = ledger;
    this.month = YearMonth.now(clock.withZone(ZoneOffset.UTC));
  }

  public synchronized void addSpend(double usd) {
    rollMonth();
    try {
      knownTotal = ledger.add(month, usd);
      refreshedAtMs = clock.millis();
    } catch (RuntimeException e) {
      knownTotal += usd;
      log.warn("Spend ledger unavailable; counting locally: {}", e.toString());
    }
    double ratio = ratio();
    while (thresholdsLogged < THRESHOLDS.length && ratio >= THRESHOLDS[thresholdsLogged]) {
      log.warn(
          "OpenAI spend reached {}% of the ${} monthly budget",
          (int) (THRESHOLDS[thresholdsLogged] * 100), monthlyBudgetUsd);
      thresholdsLogged++;
    }
  }

  public synchronized void check(JobPriority priority) {
    rollMonth();
    refreshIfStale();
    if (priority == JobPriority.NON_ESSENTIAL && ratio() >= 1.0) {
      throw new OpenAiException(
          OpenAiException.Kind.BUDGET,
          "Monthly OpenAI budget of $" + monthlyBudgetUsd + " exhausted; non-essential job paused");
    }
  }

  public synchronized double usedRatio() {
    rollMonth();
    refreshIfStale();
    return ratio();
  }

  private void refreshIfStale() {
    if (clock.millis() - refreshedAtMs < REFRESH.toMillis()) {
      return;
    }
    try {
      knownTotal = Math.max(knownTotal, ledger.get(month));
      refreshedAtMs = clock.millis();
    } catch (RuntimeException e) {
      // keep the last known total
    }
  }

  private double ratio() {
    return monthlyBudgetUsd <= 0 ? 0 : knownTotal / monthlyBudgetUsd;
  }

  private void rollMonth() {
    YearMonth now = YearMonth.now(clock.withZone(ZoneOffset.UTC));
    if (!now.equals(month)) {
      month = now;
      knownTotal = 0;
      refreshedAtMs = 0;
      thresholdsLogged = 0;
    }
  }
}
