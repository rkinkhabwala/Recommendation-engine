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
import java.util.List;
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

  static final List<String> SNAPSHOTS = List.of("v1", "v2");

  static Stream<Arguments> schemas() {
    return current()
        .flatMap(a -> SNAPSHOTS.stream().map(v -> Arguments.of(a.get()[0], a.get()[1], v)));
  }

  static Stream<Arguments> current() {
    return Stream.of(
        Arguments.of("UserEvent", UserEvent.getClassSchema()),
        Arguments.of("CatalogItem", CatalogItem.getClassSchema()),
        Arguments.of("ItemEmbedding", ItemEmbedding.getClassSchema()),
        Arguments.of("RecommendationServed", RecommendationServed.getClassSchema()),
        Arguments.of("RecommendationAttributed", RecommendationAttributed.getClassSchema()),
        Arguments.of("UserDeletionRequested", UserDeletionRequested.getClassSchema()),
        Arguments.of("OnboardingSubmitted", OnboardingSubmitted.getClassSchema()));
  }

  @ParameterizedTest(name = "{0} reads {2}")
  @MethodSource("schemas")
  void currentSchemaCanReadEverySnapshot(String name, Schema current, String version)
      throws IOException {
    Schema.Parser parser = new Schema.Parser();
    try (InputStream domain = resource(version, "Domain.avsc");
        InputStream snapshot = resource(version, name + ".avsc")) {
      parser.parse(domain);
      Schema writer = parser.parse(snapshot);
      var result = SchemaCompatibility.checkReaderWriterCompatibility(current, writer);
      assertThat(result.getType())
          .as(result.getDescription())
          .isEqualTo(SchemaCompatibilityType.COMPATIBLE);
    }
  }

  private static InputStream resource(String version, String file) {
    return SchemaCompatibilityTest.class.getResourceAsStream(
        "/schema-snapshots/" + version + "/" + file);
  }
}
