package com.recsys.api.logging;

import com.recsys.common.Topics;
import com.recsys.events.v1.RecommendationServed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.avro.specific.SpecificRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Logs served lists to {@code recs.served.v1} without ever blocking a request: the request thread
 * only offers to a bounded queue (drop + count when full); a background thread produces.
 */
public class ServedLogger implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(ServedLogger.class);

  private final KafkaTemplate<String, SpecificRecord> kafka;
  private final BlockingQueue<RecommendationServed> queue;
  private final Counter dropped;
  private final Counter failed;
  private final Thread worker;
  private volatile boolean running = true;

  public ServedLogger(
      KafkaTemplate<String, SpecificRecord> kafka, int capacity, MeterRegistry registry) {
    this.kafka = kafka;
    this.queue = new ArrayBlockingQueue<>(capacity);
    this.dropped = registry.counter("recs_api_served_log_total", "result", "dropped");
    this.failed = registry.counter("recs_api_served_log_total", "result", "failed");
    registry.gauge("recs_api_served_log_queue", queue, BlockingQueue::size);
    this.worker = Thread.ofVirtual().name("served-logger").start(this::drain);
  }

  public void log(RecommendationServed served) {
    if (!queue.offer(served)) {
      dropped.increment();
    }
  }

  private void drain() {
    while (running || !queue.isEmpty()) {
      try {
        RecommendationServed s = queue.poll(200, TimeUnit.MILLISECONDS);
        if (s != null) {
          kafka
              .send(Topics.RECS_SERVED, s.getUserId(), s)
              .whenComplete(
                  (r, e) -> {
                    if (e != null) {
                      failed.increment();
                    }
                  });
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException e) {
        failed.increment();
        log.debug("Served log send failed: {}", e.toString());
      }
    }
  }

  @Override
  public void close() throws InterruptedException {
    running = false;
    worker.join(2_000);
  }
}
