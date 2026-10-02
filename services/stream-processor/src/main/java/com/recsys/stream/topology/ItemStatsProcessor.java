package com.recsys.stream.topology;

import com.recsys.features.FeatureEnvelope;
import com.recsys.features.RedisKeys;
import com.recsys.features.model.ItemStats;
import com.recsys.stream.model.EnrichedEvent;
import com.recsys.stream.model.EventView;
import com.recsys.stream.model.ItemStatsState;
import com.recsys.stream.signals.SignalWeigher;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;

/**
 * Per-item CTR, completion and skip rates (Bayesian-smoothed) and trending velocity, from decayed
 * counters. Input is keyed by item id.
 */
final class ItemStatsProcessor implements Processor<String, EnrichedEvent, String, byte[]> {
  private static final long HOUR_MS = 3_600_000L;
  // Priors: CTR 10% (1 in 10), completion/skip 50% (1 in 2).
  private static final double CTR_A = 1;
  private static final double CTR_N = 10;

  private final TopologySettings s;
  private final SignalWeigher weigher;
  private final Set<String> dirty = new HashSet<>();
  private ProcessorContext<String, byte[]> ctx;
  private KeyValueStore<String, ItemStatsState> store;

  ItemStatsProcessor(TopologySettings settings, SignalWeigher weigher) {
    this.s = settings;
    this.weigher = weigher;
  }

  @Override
  public void init(ProcessorContext<String, byte[]> context) {
    this.ctx = context;
    this.store = context.getStateStore(StoreNames.ITEM_STATS);
    if (!s.itemEmitInterval().isZero()) {
      ctx.schedule(s.itemEmitInterval(), PunctuationType.WALL_CLOCK_TIME, this::flush);
    }
  }

  @Override
  public void process(Record<String, EnrichedEvent> record) {
    EventView e = record.value().event();
    Long duration =
        e.durationMs() != null
            ? e.durationMs()
            : record.value().profile() == null ? null : record.value().profile().durationMs();
    SignalWeigher.Kind kind = weigher.classify(e, duration);
    if (kind == SignalWeigher.Kind.OTHER) {
      return;
    }
    String item = record.key();
    ItemStatsState st = store.get(item);
    if (st == null) {
      st = new ItemStatsState();
    }
    long ts = e.eventTs();
    long hl = s.itemStatsHalfLife().toMillis();
    switch (kind) {
      case IMPRESSION -> st.impressions.add(ts, 1, hl);
      case START -> {
        st.starts.add(ts, 1, hl);
        st.plays1h.add(ts, 1, HOUR_MS);
      }
      case COMPLETE -> {
        st.completes.add(ts, 1, hl);
        st.ends.add(ts, 1, hl);
      }
      case EARLY_SKIP -> {
        st.earlySkips.add(ts, 1, hl);
        st.ends.add(ts, 1, hl);
      }
      case SKIP, END -> st.ends.add(ts, 1, hl);
      default -> {}
    }
    st.lastReceivedTs = Math.max(st.lastReceivedTs, e.receivedTs());
    long now = ctx.currentSystemTimeMs();
    if (now - st.lastEmitWallMs >= s.itemEmitInterval().toMillis()) {
      emit(item, st, now, record.timestamp());
    } else {
      dirty.add(item);
    }
    store.put(item, st);
  }

  private void emit(String item, ItemStatsState st, long wallNow, long recordTs) {
    long ref = Math.max(0, ctx.currentStreamTimeMs());
    long hl = s.itemStatsHalfLife().toMillis();
    double impressions = st.impressions.valueAt(ref, hl);
    double starts = st.starts.valueAt(ref, hl);
    double ends = st.ends.valueAt(ref, hl);
    double ctr = Math.min(1.0, (starts + CTR_A) / (impressions + CTR_N));
    double completion = (st.completes.valueAt(ref, hl) + 1) / (ends + 2);
    double skipRate = (st.earlySkips.valueAt(ref, hl) + 1) / (ends + 2);
    double plays1h = st.plays1h.valueAt(ref, HOUR_MS);
    // A decayed sum with half-life h approximates a count over h/ln2; convert both to per-hour.
    double recentRate = plays1h / (1.0 / Math.log(2));
    double baselineRate = starts / (hl / (double) HOUR_MS / Math.log(2));
    double velocity = (recentRate + 1) / (baselineRate + 1);
    st.seq++;
    st.lastEmitWallMs = wallNow;
    dirty.remove(item);
    var data =
        new ItemStats(
            item,
            Features.round(impressions),
            Features.round(starts),
            Features.round(ctr),
            Features.round(completion),
            Features.round(skipRate),
            Features.round(plays1h),
            Features.round(velocity),
            ref);
    ctx.forward(
        new Record<>(
            RedisKeys.itemStats(item),
            FeatureEnvelope.encode(st.seq, s.itemKeyTtl().toSeconds(), st.lastReceivedTs, data),
            recordTs));
  }

  private void flush(long wallNow) {
    long ts = Math.max(0, ctx.currentStreamTimeMs());
    for (String item : List.copyOf(dirty)) {
      ItemStatsState st = store.get(item);
      if (st != null) {
        emit(item, st, wallNow, ts);
        store.put(item, st);
      }
    }
    dirty.clear();
  }
}
