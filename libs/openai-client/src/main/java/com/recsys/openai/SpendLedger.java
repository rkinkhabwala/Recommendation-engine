package com.recsys.openai;

import java.time.YearMonth;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Monthly OpenAI spend shared by every worker replica (or local, for dev/tests). */
public interface SpendLedger {
  /** Adds and returns the new month total. */
  double add(YearMonth month, double usd);

  double get(YearMonth month);

  final class InMemory implements SpendLedger {
    private final Map<YearMonth, Double> spend = new ConcurrentHashMap<>();

    @Override
    public double add(YearMonth month, double usd) {
      return spend.merge(month, usd, Double::sum);
    }

    @Override
    public double get(YearMonth month) {
      return spend.getOrDefault(month, 0.0);
    }
  }
}
