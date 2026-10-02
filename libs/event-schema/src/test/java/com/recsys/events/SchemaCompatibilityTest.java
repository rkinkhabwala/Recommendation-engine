package com.recsys.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.recsys.events.v1.CatalogItem;
import com.recsys.events.v1.ItemEmbedding;
import com.recsys.events.v1.OnboardingSubmitted;
import com.recsys.events.v1.RecommendationAttributed;
import com.recsys.events.v1.RecommendationServed;
import com.recsys.events.v1.UserDeletionRequested;
import com.recsys.events.v1.UserEvent;
import java.io.IOException;
import java.io.InputStream;
import java.util.stream.Stream;
import org.apache.avro.Schema;
import org.apache.avro.SchemaCompatibility;
import org.apache.avro.SchemaCompatibility.SchemaCompatibilityType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Guards the BACKWARD compatibility rule enforced by Schema Registry: the current (reader) schema
 * must be able to read data written with every released snapshot. Add a new snapshot directory when
 * releasing a schema change; never edit an existing one.
 */
class SchemaCompatibilityTest {

  static Stream<Arguments> schemas() {
    return Stream.of(
        Arguments.of("UserEvent", UserEvent.getClassSchema()),
        Arguments.of("CatalogItem", CatalogItem.getClassSchema()),
        Arguments.of("ItemEmbedding", ItemEmbedding.getClassSchema()),
        Arguments.of("RecommendationServed", RecommendationServed.getClassSchema()),
        Arguments.of("RecommendationAttributed", RecommendationAttributed.getClassSchema()),
        Arguments.of("UserDeletionRequested", UserDeletionRequested.getClassSchema()),
        Arguments.of("OnboardingSubmitted", OnboardingSubmitted.getClassSchema()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("schemas")
  void currentSchemaCanReadV1Snapshot(String name, Schema current) throws IOException {
    Schema.Parser parser = new Schema.Parser();
    try (InputStream domain = resource("Domain.avsc");
        InputStream snapshot = resource(name + ".avsc")) {
      parser.parse(domain);
      Schema writer = parser.parse(snapshot);
      var result = SchemaCompatibility.checkReaderWriterCompatibility(current, writer);
      assertThat(result.getType())
          .as(result.getDescription())
          .isEqualTo(SchemaCompatibilityType.COMPATIBLE);
    }
  }

  private static InputStream resource(String file) {
    return SchemaCompatibilityTest.class.getResourceAsStream("/schema-snapshots/v1/" + file);
  }
}
