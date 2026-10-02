package com.recsys.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.recsys.common.Vectors;
import java.util.List;
import org.junit.jupiter.api.Test;

class MockEmbeddingClientTest {
  final MockEmbeddingClient client = new MockEmbeddingClient("mock", 512, null);

  float[] embed(String s) {
    return client.embed(List.of(s), "test", JobPriority.ESSENTIAL).vectors().get(0);
  }

  @Test
  void deterministicAndUnitLength() {
    float[] a = embed("Blue Train by Coltrane. Genres: jazz. Mood: mellow");
    assertThat(embed("Blue Train by Coltrane. Genres: jazz. Mood: mellow")).containsExactly(a);
    assertThat(Vectors.norm(a)).isBetween(0.999f, 1.001f);
    assertThat(a).hasSize(512);
  }

  @Test
  void sharedMetadataMeansHigherSimilarity() {
    float[] jazz1 =
        embed("Night Walk by Miles Quartet. Genres: jazz, bebop. Mood: mellow, late-night");
    float[] jazz2 = embed("Moon Song by Ella Trio. Genres: jazz, bebop. Mood: mellow, romantic");
    float[] metal =
        embed("Iron Storm by Steel Ravens. Genres: metal, thrash. Mood: aggressive, loud");
    assertThat(Vectors.cosine(jazz1, jazz2)).isGreaterThan(Vectors.cosine(jazz1, metal) + 0.2f);
  }

  @Test
  void scrubsDirectIdentifiers() {
    String s =
        PiiScrubber.scrub(
            "I'm jane.doe@example.com, call +1 (415) 555-0100, @janed, see https://x.io/me. Love jazz");
    assertThat(s)
        .doesNotContain("jane.doe", "555", "@janed", "x.io")
        .contains("[email]", "[phone]", "[handle]", "[url]", "Love jazz");
  }
}
