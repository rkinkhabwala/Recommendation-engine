package com.recsys.stream.serde;

import com.recsys.events.AvroTrust;
import com.recsys.events.v1.CatalogItem;
import com.recsys.events.v1.ItemEmbedding;
import com.recsys.events.v1.RecommendationAttributed;
import com.recsys.events.v1.RecommendationServed;
import com.recsys.events.v1.UserDeletionRequested;
import com.recsys.events.v1.UserEvent;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import java.util.Map;
import org.apache.avro.specific.SpecificRecord;

/** Avro serdes for contract topics, configured against Schema Registry. */
public final class StreamSerdes {
  public final SpecificAvroSerde<UserEvent> userEvent;
  public final SpecificAvroSerde<CatalogItem> catalogItem;
  public final SpecificAvroSerde<ItemEmbedding> itemEmbedding;
  public final SpecificAvroSerde<RecommendationServed> served;
  public final SpecificAvroSerde<RecommendationAttributed> attributed;
  public final SpecificAvroSerde<UserDeletionRequested> deletion;

  public StreamSerdes(String schemaRegistryUrl) {
    AvroTrust.install();
    Map<String, Object> config =
        Map.of("schema.registry.url", schemaRegistryUrl, "auto.register.schemas", true);
    userEvent = avro(config);
    catalogItem = avro(config);
    itemEmbedding = avro(config);
    served = avro(config);
    attributed = avro(config);
    deletion = avro(config);
  }

  private static <T extends SpecificRecord> SpecificAvroSerde<T> avro(Map<String, Object> config) {
    SpecificAvroSerde<T> serde = new SpecificAvroSerde<>();
    serde.configure(config, false);
    return serde;
  }
}
