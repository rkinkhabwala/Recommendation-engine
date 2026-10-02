package com.recsys.enrichment;

import com.recsys.common.Topics;
import com.recsys.events.v1.CatalogItem;
import com.recsys.events.v1.RecommendationServed;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class Listeners {
  private final ItemEnricher enricher;
  private final ExplanationGenerator explainer;

  Listeners(ItemEnricher enricher, ExplanationGenerator explainer) {
    this.enricher = enricher;
    this.explainer = explainer;
  }

  @KafkaListener(id = "item-enricher", topics = Topics.CATALOG_ITEMS, batch = "true")
  void onCatalog(List<ConsumerRecord<String, CatalogItem>> records) {
    for (var r : records) {
      enricher.process(r.value());
    }
  }

  @KafkaListener(id = "explainer", topics = Topics.RECS_SERVED, batch = "true")
  void onServed(List<ConsumerRecord<String, RecommendationServed>> records) {
    for (var r : records) {
      if (r.value() != null) {
        explainer.process(r.value());
      }
    }
  }
}
