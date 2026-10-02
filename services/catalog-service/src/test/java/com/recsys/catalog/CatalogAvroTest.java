package com.recsys.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.recsys.events.v1.CatalogItem;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CatalogAvroTest {
  @Test
  void catalogItemSerializesWithLogicalTypes() {
    var dto =
        new CatalogItemDto(
            "s_1",
            "song",
            "Blue",
            "a_1",
            "Artist",
            List.of("jazz"),
            List.of("calm"),
            200_000L,
            LocalDate.of(2026, 9, 1),
            false,
            List.of("US"),
            null,
            null,
            List.of("night"),
            List.of(),
            "calm",
            null,
            1,
            1L,
            Instant.now(),
            Instant.now());
    com.recsys.events.AvroTrust.install();
    CatalogItem avro = CatalogPublisher.toAvro(dto);
    try (var ser = new KafkaAvroSerializer()) {
      ser.configure(Map.of("schema.registry.url", "mock://catalog-test"), false);
      byte[] bytes = ser.serialize("catalog.items.v1", avro);
      try (var de = new io.confluent.kafka.serializers.KafkaAvroDeserializer()) {
        de.configure(
            Map.of("schema.registry.url", "mock://catalog-test", "specific.avro.reader", true),
            false);
        CatalogItem back = (CatalogItem) de.deserialize("catalog.items.v1", bytes);
        assertThat(back.getReleaseDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(back.getAvailableRegions()).containsExactly("US");
        assertThat(back.getThemes()).containsExactly("night");
        assertThat(back.getEnrichmentVersion()).isEqualTo(1);
      }
    }
  }
}
