package com.recsys.stream.topology;

import com.recsys.features.FeatureEnvelope;
import com.recsys.features.RedisKeys;
import com.recsys.features.model.ScoredItem;
import com.recsys.features.model.TrendingList;
import com.recsys.stream.model.PartialTop;
import com.recsys.stream.model.TrendingMergeState;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;

/**
 * Stage 2: merges per-partition partial top-N lists into the global trending list for one
 * domain+region. Volume is tiny (partitions × regions per interval). Partials from partitions that
 * stopped reporting (rebalanced away) expire.
 */
final class TrendingMergeProcessor implements Processor<String, PartialTop, String, byte[]> {
  private static final long PARTIAL_TTL_MS = 5 * 60_000L;

  private final TopologySettings s;
  private KeyValueStore<String, TrendingMergeState> store;
  private ProcessorContext<String, byte[]> ctx;

  TrendingMergeProcessor(TopologySettings settings) {
    this.s = settings;
  }

  @Override
  public void init(ProcessorContext<String, byte[]> context) {
    this.ctx = context;
    this.store = context.getStateStore(StoreNames.TRENDING_MERGE);
  }

  @Override
  public void process(Record<String, PartialTop> record) {
    PartialTop partial = record.value();
    TrendingMergeState st = store.get(record.key());
    if (st == null) {
      st = new TrendingMergeState();
    }
    st.partials.put(partial.partition(), partial);
    st.partials.values().removeIf(p -> partial.wallTs() - p.wallTs() > PARTIAL_TTL_MS);

    Map<String, Double> merged = new HashMap<>();
    for (PartialTop p : st.partials.values()) {
      for (ScoredItem si : p.items()) {
        merged.merge(si.itemId(), si.score(), Math::max);
      }
    }
    List<ScoredItem> top =
        merged.entrySet().stream()
            .map(e -> new ScoredItem(e.getKey(), e.getValue()))
            .sorted(Comparator.comparingDouble(ScoredItem::score).reversed())
            .limit(s.trendingTopN())
            .toList();
    st.seq++;
    store.put(record.key(), st);

    String[] parts = record.key().split("\\|", 2);
    String domain = parts[0];
    String region = parts.length > 1 ? parts[1] : "GLOBAL";
    var data = new TrendingList(domain, region, top, Math.max(0, ctx.currentStreamTimeMs()));
    ctx.forward(
        new Record<>(
            RedisKeys.trending(domain, region),
            FeatureEnvelope.encode(st.seq, 86_400, 0, data),
            record.timestamp()));
  }
}
