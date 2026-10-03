package com.recsys.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class BudgetGuardTest {
  @Test
  void replicasShareOneBudgetThroughTheLedger() {
    var ledger = new SpendLedger.InMemory(); // stands in for Redis
    var a = new BudgetGuard(10, Clock.systemUTC(), ledger);
    var b = new BudgetGuard(10, Clock.systemUTC(), ledger);
    a.addSpend(6);
    b.addSpend(5); // fleet total 11 > 10
    assertThatThrownBy(() -> b.check(JobPriority.NON_ESSENTIAL))
        .isInstanceOf(OpenAiException.class);
    b.check(JobPriority.ESSENTIAL); // essential jobs keep running
    assertThat(b.usedRatio()).isEqualTo(1.1);
  }

  @Test
  void ledgerOutageFallsBackToLocalCounting() {
    SpendLedger broken =
        new SpendLedger() {
          public double add(YearMonth m, double usd) {
            throw new RuntimeException("redis down");
          }

          public double get(YearMonth m) {
            throw new RuntimeException("redis down");
          }
        };
    var g = new BudgetGuard(1, Clock.systemUTC(), broken);
    g.addSpend(2);
    assertThatThrownBy(() -> g.check(JobPriority.NON_ESSENTIAL))
        .isInstanceOf(OpenAiException.class);
  }
}
