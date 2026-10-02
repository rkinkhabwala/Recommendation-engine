package com.recsys.embedding;

import com.recsys.common.Topics;
import com.recsys.events.v1.CatalogItem;
import com.recsys.events.v1.OnboardingSubmitted;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class Listeners {
  private final CatalogEmbedder embedder;
  private final OnboardingSeeder seeder;

  Listeners(CatalogEmbedder embedder, OnboardingSeeder seeder) {
    this.embedder = embedder;
    this.seeder = seeder;
  }

  @KafkaListener(id = "catalog-embedder", topics = Topics.CATALOG_ITEMS, batch = "true")
  void onCatalog(List<ConsumerRecord<String, CatalogItem>> records) {
    // Keep only the latest version per item within the batch (the topic is compacted anyway).
    Map<String, CatalogItem> latest = new LinkedHashMap<>();
    for (var r : records) {
      CatalogItem prev = latest.get(r.key());
      if (r.value() == null || prev == null || r.value().getSeq() >= prev.getSeq()) {
        latest.put(r.key(), r.value());
      }
    }
    embedder.process(latest);
  }

  @KafkaListener(id = "onboarding-seeder", topics = Topics.USERS_ONBOARDING, batch = "true")
  void onOnboarding(List<ConsumerRecord<String, OnboardingSubmitted>> records) throws Exception {
    for (var r : records) {
      if (r.value() != null) {
        seeder.seed(r.value());
      }
    }
  }
}
