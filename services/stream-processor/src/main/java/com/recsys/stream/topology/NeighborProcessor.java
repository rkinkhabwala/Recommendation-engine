package com.recsys.stream.topology;

import com.recsys.common.DecayedScalar;
import com.recsys.features.FeatureEnvelope;
import com.recsys.features.RedisKeys;
import com.recsys.features.model.Neighbors;
import com.recsys.stream.model.CoEmit;
import com.recsys.stream.model.NeighborState;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;

/**
 * Item-item co-engagement ({@code i:{id}:i2i}) and session next-item ({@code i:{id}:next})
 * neighbour lists from decayed counts. Hot items are throttled to one emission per interval.
 */
final class NeighborProcessor implements Processor<String, CoEmit, String, byte[]> {
  private static final int MAX_TRACKED = 200;

  private final TopologySettings s;
  private final Set<String> dirtyNext = new HashSet<>();
  private final Set<String> dirtyCo = new HashSet<>();
  private ProcessorContext<String, byte[]> ctx;
  private KeyValueStore<String, NeighborState> store;

  NeighborProcessor(TopologySettings settings) {
    this.s = settings;
  }

  @Override
  public void init(ProcessorContext<String, byte[]> context) {
    this.ctx = context;
    this.store = context.getStateStore(StoreNames.NEIGHBORS);
    if (!s.itemEmitInterval().isZero()) {
      ctx.schedule(s.itemEmitInterval(), PunctuationType.WALL_CLOCK_TIME, this::flush);
    }
  }

  @Override
  public void process(Record<String, CoEmit> record) {
    CoEmit c = record.value();
    String item = record.key();
    NeighborState st = store.get(item);
    if (st == null) {
      st = new NeighborState();
    }
    long hl = s.neighborHalfLife().toMillis();
    boolean next = CoEmit.NEXT.equals(c.kind());
    Map<String, DecayedScalar> map = next ? st.next : st.co;
    Features.add(map, c.toItem(), c.weight(), c.ts(), hl);
    Features.prune(map, MAX_TRACKED, c.ts(), hl);
    st.lastReceivedTs = Math.max(st.lastReceivedTs, c.receivedTs());

    long now = ctx.currentSystemTimeMs();
    long interval = s.itemEmitInterval().toMillis();
    if (next) {
      if (now - st.lastEmitNextWallMs >= interval) {
        emit(item, st, true, now, record.timestamp());
      } else {
        dirtyNext.add(item);
      }
    } else if (now - st.lastEmitCoWallMs >= interval) {
      emit(item, st, false, now, record.timestamp());
    } else {
      dirtyCo.add(item);
    }
    store.put(item, st);
  }

  private void emit(String item, NeighborState st, boolean next, long wallNow, long recordTs) {
    long hl = s.neighborHalfLife().toMillis();
    long refTs = Math.max(0, ctx.currentStreamTimeMs());
    var top =
        Features.topK(next ? st.next : st.co, s.neighborTopK(), s.neighborMinSupport(), refTs, hl);
    if (top.isEmpty()) {
      return;
    }
    long seq;
    if (next) {
      seq = ++st.seqNext;
      st.lastEmitNextWallMs = wallNow;
      dirtyNext.remove(item);
    } else {
      seq = ++st.seqCo;
      st.lastEmitCoWallMs = wallNow;
      dirtyCo.remove(item);
    }
    String key = next ? RedisKeys.itemNext(item) : RedisKeys.itemNeighbors(item);
    var data = new Neighbors(item, next ? "next" : "i2i", top, refTs);
    ctx.forward(
        new Record<>(
            key,
            FeatureEnvelope.encode(seq, s.itemKeyTtl().toSeconds(), st.lastReceivedTs, data),
            recordTs));
  }

  private void flush(long wallNow) {
    long ts = Math.max(0, ctx.currentStreamTimeMs());
    for (String item : List.copyOf(dirtyNext)) {
      NeighborState st = store.get(item);
      if (st != null) {
        emit(item, st, true, wallNow, ts);
        store.put(item, st);
      }
    }
    for (String item : List.copyOf(dirtyCo)) {
      NeighborState st = store.get(item);
      if (st != null) {
        emit(item, st, false, wallNow, ts);
        store.put(item, st);
      }
    }
    dirtyNext.clear();
    dirtyCo.clear();
  }
}
