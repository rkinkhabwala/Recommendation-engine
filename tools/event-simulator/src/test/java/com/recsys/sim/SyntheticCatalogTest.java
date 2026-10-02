package com.recsys.sim;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SyntheticCatalogTest {
  @Test
  void deterministicWithRealisticShape() {
    var day = LocalDate.of(2026, 10, 2);
    var a = new SyntheticCatalog(5000, 42, day);
    var b = new SyntheticCatalog(5000, 42, day);
    assertThat(a.songs().get(123)).isEqualTo(b.songs().get(123));
    assertThat(a.songs().get(0).id()).isEqualTo("s_000001");
    // Genre popularity is skewed (head genres much bigger than tail)
    assertThat(a.inGenre("pop").size()).isGreaterThan(a.inGenre("techno").size() * 3);
    long fresh = a.songs().stream().filter(s -> s.releaseDate().isAfter(day.minusDays(14))).count();
    assertThat(fresh).isBetween(50L, 200L);
  }

  @Test
  void affinityPrefersMatchingGenres() {
    var catalog = new SyntheticCatalog(2000, 42, LocalDate.of(2026, 10, 2));
    var jazzFan =
        new UserProfile(
            "u", java.util.Map.of("jazz", 1.0), java.util.List.of("mellow"), "US", "web", 0.5);
    var jazz = catalog.inGenre("jazz").get(0);
    var metal = catalog.inGenre("metal").get(0);
    assertThat(jazzFan.affinity(jazz)).isGreaterThan(jazzFan.affinity(metal));
  }
}
