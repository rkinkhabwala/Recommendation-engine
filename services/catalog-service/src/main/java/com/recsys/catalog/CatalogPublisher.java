package com.recsys.catalog;

import com.recsys.common.Topics;
import com.recsys.events.v1.CatalogItem;
import com.recsys.events.v1.Domain;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes a committed catalog version to Kafka ({@code catalog.items.v1}, compacted) and records
 * it as published. Serving metadata in Redis is derived from that topic by the stream processor, so
 * Redis can always be rebuilt from Kafka. A crash between the DB commit and this call is repaired
 * by {@link CatalogReconciler}. TODO(phase-3): transactional outbox / CDC.
 */
@Component
public class CatalogPublisher {
  private final KafkaTemplate<String, SpecificRecord> kafka;
  private final CatalogRepository repository;

  public CatalogPublisher(
      KafkaTemplate<String, SpecificRecord> kafka, CatalogRepository repository) {
    this.kafka = kafka;
    this.repository = repository;
  }

  public void publish(CatalogItemDto item) throws Exception {
    kafka.send(Topics.CATALOG_ITEMS, item.itemId(), toAvro(item)).get(10, TimeUnit.SECONDS);
    repository.markPublished(item.itemId(), item.seq());
  }

  public void publishDeletion(String itemId, long seq) throws Exception {
    kafka.send(Topics.CATALOG_ITEMS, itemId, null).get(10, TimeUnit.SECONDS); // tombstone
    repository.markPublished(itemId, seq);
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
        .setCreatedAt(i.createdAt())
        .setUpdatedAt(i.updatedAt())
        .setSeq(i.seq())
        .build();
  }
}
