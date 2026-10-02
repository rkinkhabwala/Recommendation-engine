package com.recsys.vector;

import static org.assertj.core.api.Assertions.assertThat;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.qdrant.QdrantContainer;

@Testcontainers
class QdrantVectorIndexIT {
  @Container static final QdrantContainer QDRANT = new QdrantContainer("qdrant/qdrant:v1.19.1");

  static QdrantClient client;
  static QdrantVectorIndex index;
  static final Duration T = Duration.ofSeconds(5);

  @BeforeAll
  static void setUp() {
    client =
        new QdrantClient(
            QdrantGrpcClient.newBuilder(QDRANT.getHost(), QDRANT.getGrpcPort(), false).build());
    index = new QdrantVectorIndex(client, T);
    index.ensureCollection("items_test_3_v1", 3, "items_current");
    index.upsert(
        "items_test_3_v1",
        List.of(
            item("s_jazz", new float[] {1, 0, 0}, false, List.of(), 1_000),
            item("s_rock", new float[] {0, 1, 0}, true, List.of("US"), 2_000),
            item("s_pop", new float[] {0.7f, 0.7f, 0}, false, List.of("GB"), 3_000)));
  }

  @AfterAll
  static void tearDown() {
    client.close();
  }

  static IndexedItem item(String id, float[] v, boolean explicit, List<String> regions, long ts) {
    return new IndexedItem(
        new ItemPayload(
            id,
            "song",
            "a_" + id,
            List.of("g"),
            List.of("m"),
            explicit,
            regions,
            ts,
            "h_" + id,
            "items_test_3_v1"),
        v);
  }

  @Test
  void searchesThroughAliasWithFilters() {
    var hits =
        index.search(
            "items_current",
            new float[] {1, 0.1f, 0},
            new SearchFilter("song", "US", false, null, List.of()),
            10,
            T);
    // s_rock is explicit, s_pop is GB-only
    assertThat(hits).extracting(VectorHit::itemId).containsExactly("s_jazz");
    assertThat(hits.get(0).payload().artistId())
        .isEqualTo("a_s_jazz"); // search returns a slim payload
  }

  @Test
  void ingestedAfterAndExcludes() {
    var hits =
        index.search(
            "items_current",
            new float[] {1, 1, 0},
            new SearchFilter("song", null, true, 1_500L, List.of("s_pop")),
            10,
            T);
    assertThat(hits).extracting(VectorHit::itemId).containsExactly("s_rock");
  }

  @Test
  void hashesVectorsAndScoreIds() {
    assertThat(index.contentHashes("items_test_3_v1", List.of("s_jazz", "missing")))
        .containsOnlyKeys("s_jazz")
        .containsEntry("s_jazz", "h_s_jazz");
    assertThat(index.vectors("items_test_3_v1", List.of("s_rock")).get("s_rock"))
        .containsExactly(0, 1, 0);
    var scored =
        index.scoreIds("items_current", new float[] {0, 1, 0}, List.of("s_rock", "s_jazz"), T);
    assertThat(scored).hasSize(2);
    assertThat(scored.get(0).itemId()).isEqualTo("s_rock");
  }
}
