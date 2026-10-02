package com.recsys.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.recsys.events.AvroTrust;
import com.recsys.events.v1.UserEvent;
import com.recsys.ingestion.web.EventMapper;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The real Confluent serializer path (the controller test mocks the publisher). */
class EventAvroTest {
  @Test
  void mappedEventsRoundTripThroughSchemaRegistrySerde() {
    AvroTrust.install();
    Instant now = Instant.now();
    UserEvent avro =
        EventMapper.toAvro(
            EventValidatorTest.event("skip", "s_1", now), now, Duration.ofSeconds(60));
    var conf = Map.of("schema.registry.url", "mock://ingestion-test", "specific.avro.reader", true);
    try (var ser = new KafkaAvroSerializer();
        var de = new KafkaAvroDeserializer()) {
      ser.configure(conf, false);
      de.configure(conf, false);
      UserEvent back =
          (UserEvent) de.deserialize("events.raw.v1", ser.serialize("events.raw.v1", avro));
      assertThat(back).isEqualTo(avro);
    }
  }
}
