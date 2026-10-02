package com.recsys.stream.topology;

import com.recsys.features.model.ScoredItem;
import com.recsys.stream.model.PartialTop;
import com.recsys.stream.model.TrendingBuckets;
import com.recsys.stream.model.WindowCount;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;

/**
 * Stage 1 of the two-stage trending top-K. Input: event-time windowed play counts keyed by {@code
 * domain|region|item} (spread across partitions). Keeps the last 2h of window counts per key,
 * scores {@code lastHour × sqrt(min(velocity, 4))} and periodically emits this task's local top-N
 * per domain+region to the merger. This avoids routing every play through one hot "region" key.
 */
final class TrendingProcessor implements Processor<String, WindowCount, String, PartialTop> {
  private static final long HOUR_MS = 3_600_000L;

  private final TopologySettings s;

  /** domain|region → item → (score, latest window start). In-memory, rebuilt by new counts. */
  private final Map<String, Map<String, double[]>> local = new HashMap<>();

  private ProcessorContext<String, PartialTop> ctx;
  private KeyValueStore<String, TrendingBuckets> store;

  TrendingProcessor(TopologySettings settings) {
    this.s = settings;
  }

  @Override
  public void init(ProcessorContext<String, PartialTop> context) {
    this.ctx = context;
    this.store = context.getStateStore(StoreNames.TRENDING_BUCKETS);
    ctx.schedule(
        s.trendingEmitInterval().isZero()
            ? java.time.Duration.ofSeconds(1)
            : s.trendingEmitInterval(),
        PunctuationType.WALL_CLOCK_TIME,
        this::emit);
  }

  @Override
  public void process(Record<String, WindowCount> record) {
    WindowCount wc = record.value();
    TrendingBuckets b = store.get(record.key());
    if (b == null) {
      b = new TrendingBuckets();
    }
    b.counts.put(wc.windowStart(), wc.count()); // absolute count: replays are harmless
    long now = Math.max(ctx.currentStreamTimeMs(), wc.windowStart());
    b.counts.headMap(now - 2 * HOUR_MS).clear();
    store.put(record.key(), b);

    long lastHour =
        b.counts.tailMap(now - HOUR_MS).values().stream().mapToLong(Long::longValue).sum();
    long prevHour =
        b.counts.subMap(now - 2 * HOUR_MS, now - HOUR_MS).values().stream()
            .mapToLong(Long::longValue)
            .sum();
    double velocity = Math.min((lastHour + 2.0) / (prevHour + 2.0), 4.0);
    double score = lastHour * Math.sqrt(velocity);
    local
        .computeIfAbsent(wc.domainRegion(), k -> new HashMap<>())
        .put(wc.itemId(), new double[] {score, wc.windowStart()});
  }

  private void emit(long wallNow) {
    long streamNow = ctx.currentStreamTimeMs();
    int partition = ctx.taskId().partition();
    for (var region : local.entrySet()) {
      Map<String, double[]> items = region.getValue();
      items.values().removeIf(v -> v[1] < streamNow - 2 * HOUR_MS);
      List<ScoredItem> top =
          items.entrySet().stream()
              .map(e -> new ScoredItem(e.getKey(), Features.round(e.getValue()[0])))
              .filter(si -> si.score() > 0)
              .sorted(Comparator.comparingDouble(ScoredItem::score).reversed())
              .limit(s.trendingTopN())
              .toList();
      // Bound memory: keep only candidates that could still make the list.
      if (items.size() > s.trendingTopN() * 4) {
        var keep = top.stream().map(ScoredItem::itemId).toList();
        items.keySet().retainAll(keep);
      }
      ctx.forward(
          new Record<>(
              region.getKey(),
              new PartialTop(region.getKey(), partition, top, wallNow),
              Math.max(0, streamNow)));
    }
  }
}
