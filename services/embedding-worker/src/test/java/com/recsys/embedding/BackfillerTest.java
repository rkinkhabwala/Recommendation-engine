package com.recsys.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import com.recsys.events.v1.ItemEmbedding;
import com.recsys.openai.JobPriority;
import com.recsys.openai.MockEmbeddingClient;
import com.recsys.vector.InMemoryVectorIndex;
import com.recsys.vector.SearchFilter;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BackfillerTest {
  @Test
  void buildsNewIndexAndTopicThenSwitchesAlias() {
    var index = new InMemoryVectorIndex();
    index.ensureCollection("items_mock_64_v1", 64, "items_current"); // live index
    var mock = new MockEmbeddingClient("mock", 64, null);
    Map<String, List<ItemEmbedding>> published = new HashMap<>();
    var backfiller =
        new Backfiller(
            index,
            texts -> {
              Map<String, float[]> out = new HashMap<>();
              texts.forEach(
                  (id, t) ->
                      out.put(
                          id,
                          mock.embed(List.of(t), "test", JobPriority.NON_ESSENTIAL)
                              .vectors()
                              .get(0)));
              return out;
            },
            (topic, records) ->
                published.computeIfAbsent(topic, k -> new ArrayList<>()).addAll(records),
            Clock.systemUTC());
    Map<String, com.recsys.events.v1.CatalogItem> catalog = new HashMap<>();
    catalog.put("s1", CatalogEmbedderTest.song("s1", "Blue", List.of()));
    catalog.put("s2", CatalogEmbedderTest.song("s2", "Green", List.of()));
    catalog.put("s3", null); // deleted

    var result =
        backfiller.run(catalog, "items_mock_64_v2", 64, "mock", "v2", "items_current", true, 1);

    assertThat(result.embedded()).isEqualTo(2);
    assertThat(result.aliasSwitched()).isTrue();
    assertThat(published.get("catalog.embeddings.items_mock_64_v2"))
        .extracting(ItemEmbedding::getIndexVersion)
        .containsOnly("items_mock_64_v2");
    assertThat(index.search("items_current", new float[64], SearchFilter.domain("song"), 10, null))
        .hasSize(2);
  }
}
