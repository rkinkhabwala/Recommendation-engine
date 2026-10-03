package com.recsys.catalog;

import com.recsys.common.Topics;
import com.recsys.events.v1.CatalogItem;
import com.recsys.events.v1.Domain;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Kafka side of the outbox: publishes the latest committed version of an item to {@code
 * catalog.items.v1} (compacted). Serving metadata in Redis is derived from that topic by the stream
 * processor, so Redis can always be rebuilt from Kafka.
 */
@Component
public class CatalogPublisher implements OutboxRelay.Sink {
  private final KafkaTemplate<String, SpecificRecord> kafka;

  public CatalogPublisher(KafkaTemplate<String, SpecificRecord> kafka) {
    this.kafka = kafka;
  }

  @Override
  public CompletableFuture<?> publish(CatalogItemDto item) {
    return kafka.send(Topics.CATALOG_ITEMS, item.itemId(), toAvro(item));
  }

  @Override
  public CompletableFuture<?> tombstone(String itemId) {
    return kafka.send(Topics.CATALOG_ITEMS, itemId, null);
  }

  static CatalogItem toAvro(CatalogItemDto i) {
    return CatalogItem.newBuilder()
        .setItemId(i.itemId())
        .setDomain(Domain.valueOf(i.domain().toUpperCase(Locale.ROOT)))
        .setTitle(i.title())
        .setCreatorId(i.artistId())
        .setCreatorName(i.artistName())
        .setGenres(i.genres())
        .setMoodTags(i.moods())
        .setDurationMs(i.durationMs())
        .setReleaseDate(i.releaseDate())
        .setExplicit(i.explicit())
        .setAvailableRegions(i.availableRegions())
        .setDescription(i.description())
        .setTranscriptSummary(i.transcriptSummary())
        .setThemes(i.themes())
        .setTopics(i.topics())
        .setTone(i.tone())
        .setReadingLevel(i.readingLevel())
        .setEnrichmentVersion(i.enrichmentVersion())
        .setCreatedAt(i.createdAt())
        .setUpdatedAt(i.updatedAt())
        .setSeq(i.seq())
        .build();
  }
}
