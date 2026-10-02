package com.recsys.stream.topology;

import com.recsys.events.v1.UserEvent;
import com.recsys.stream.model.Deduped;
import java.time.Duration;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.WindowStore;

/**
 * Drops events whose event_id was already seen within the dedupe window and flags events older than
 * the allowed lateness (relative to stream time). Input is keyed by user_id and an event id always
 * belongs to one user, so the window store is partition-local — no repartition needed.
 */
final class DedupeProcessor implements Processor<String, UserEvent, String, Deduped> {
  private final long windowMs;
  private final long maxLatenessMs;
  private ProcessorContext<String, Deduped> ctx;
  private WindowStore<String, Long> seen;

  DedupeProcessor(Duration window, Duration maxLateness) {
    this.windowMs = window.toMillis();
    this.maxLatenessMs = maxLateness.toMillis();
  }

  @Override
  public void init(ProcessorContext<String, Deduped> context) {
    this.ctx = context;
    this.seen = context.getStateStore(StoreNames.DEDUPE);
  }

  @Override
  public void process(Record<String, UserEvent> record) {
    UserEvent e = record.value();
    if (e == null) {
      return;
    }
    long ts = e.getEventTs().toEpochMilli();
    if (ts < ctx.currentStreamTimeMs() - maxLatenessMs) {
      ctx.forward(record.withValue(new Deduped(e, true)));
      return;
    }
    String id = e.getEventId();
    try (var it = seen.fetch(id, ts - windowMs, ts + windowMs)) {
      if (it.hasNext()) {
        return; // duplicate (client retry or producer re-send)
      }
    }
    seen.put(id, ts, ts);
    ctx.forward(record.withValue(new Deduped(e, false)));
  }
}
