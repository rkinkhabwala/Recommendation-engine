package com.recsys.sim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

final class Stats {
  final LongAdder eventsSent = new LongAdder();
  final LongAdder eventsRejected = new LongAdder();
  final LongAdder recRequests = new LongAdder();
  final LongAdder recErrors = new LongAdder();
  final LongAdder plays = new LongAdder();
  final LongAdder completes = new LongAdder();
  final LongAdder earlySkips = new LongAdder();
  final LongAdder explained = new LongAdder();
  final Map<String, LongAdder> fallback = new ConcurrentHashMap<>();
  private final List<Long> latenciesMicros = Collections.synchronizedList(new ArrayList<>());
  final AtomicLong started = new AtomicLong(System.nanoTime());
  final Map<String, LongAdder> errors = new ConcurrentHashMap<>();

  void error(Exception e) {
    String key =
        e.getClass().getSimpleName()
            + ": "
            + String.valueOf(e.getMessage()).replaceAll("[0-9a-f-]{36}", "<id>");
    errors
        .computeIfAbsent(key.length() > 140 ? key.substring(0, 140) : key, k -> new LongAdder())
        .increment();
  }

  void latency(long micros) {
    if (latenciesMicros.size() < 2_000_000) {
      latenciesMicros.add(micros);
    }
  }

  String percentiles() {
    List<Long> copy;
    synchronized (latenciesMicros) {
      copy = new ArrayList<>(latenciesMicros);
    }
    if (copy.isEmpty()) {
      return "n/a";
    }
    Collections.sort(copy);
    return "p50=%.1fms p95=%.1fms p99=%.1fms"
        .formatted(p(copy, 0.5) / 1000.0, p(copy, 0.95) / 1000.0, p(copy, 0.99) / 1000.0);
  }

  private static long p(List<Long> sorted, double q) {
    return sorted.get(Math.min(sorted.size() - 1, (int) Math.floor(q * sorted.size())));
  }

  String line() {
    double secs = (System.nanoTime() - started.get()) / 1e9;
    double completion = plays.sum() == 0 ? 0 : (double) completes.sum() / plays.sum();
    double skip = plays.sum() == 0 ? 0 : (double) earlySkips.sum() / plays.sum();
    return "events=%d (%.0f/s, rejected=%d) recs=%d errors=%d client-latency[%s] completion=%.1f%% early-skip=%.1f%% explained=%d fallback=%s"
        .formatted(
            eventsSent.sum(),
            eventsSent.sum() / Math.max(1, secs),
            eventsRejected.sum(),
            recRequests.sum(),
            recErrors.sum(),
            percentiles(),
            completion * 100,
            skip * 100,
            explained.sum(),
            fallback);
  }
}
