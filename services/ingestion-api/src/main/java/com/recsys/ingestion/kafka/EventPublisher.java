package com.recsys.ingestion.kafka;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Produces records and waits for broker acks (acks=all, idempotent producer). Returning success
 * only after the ack gives clients at-least-once semantics: on failure they retry with the same
 * event id and the stream processor dedupes.
 */
@Component
public class EventPublisher {
  private final KafkaTemplate<String, SpecificRecord> kafka;

  public EventPublisher(KafkaTemplate<String, SpecificRecord> kafka) {
    this.kafka = kafka;
  }

  public record Outgoing(String topic, String key, Instant timestamp, SpecificRecord value) {}

  /** Returns one boolean per record: true if acked within the timeout. */
  public List<Boolean> publish(List<Outgoing> records, Duration timeout) {
    List<CompletableFuture<?>> futures = new ArrayList<>(records.size());
    for (Outgoing r : records) {
      futures.add(
          kafka.send(
              new ProducerRecord<>(
                  r.topic(), null, r.timestamp().toEpochMilli(), r.key(), r.value())));
    }
    long deadline = System.nanoTime() + timeout.toNanos();
    List<Boolean> acked = new ArrayList<>(records.size());
    for (CompletableFuture<?> f : futures) {
      try {
        f.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        acked.add(true);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        acked.add(false);
      } catch (Exception e) {
        acked.add(false);
      }
    }
    return acked;
  }
}
