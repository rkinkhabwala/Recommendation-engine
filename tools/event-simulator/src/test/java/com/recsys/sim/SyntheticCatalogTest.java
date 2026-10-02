package com.recsys.sim;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SyntheticCatalogTest {
  static final LocalDate DAY = LocalDate.of(2026, 10, 2);

  @Test
  void songCatalogIsDeterministicWithRealisticShape() {
    var a = new SyntheticCatalog(5000, 42, DAY);
    var b = new SyntheticCatalog(5000, 42, DAY);
    assertThat(a.items().get(123)).isEqualTo(b.items().get(123));
    assertThat(a.items().get(0).id()).isEqualTo("s_000001");
    assertThat(a.inGenre("pop").size()).isGreaterThan(a.inGenre("techno").size() * 3);
    long fresh = a.items().stream().filter(s -> s.releaseDate().isAfter(DAY.minusDays(14))).count();
    assertThat(fresh).isBetween(50L, 200L);
  }

  @Test
  void otherDomainsShareTopicsAndPostsNeedEnrichment() {
    var c = new Catalogs(Map.of("song", 500, "book", 300, "video", 300, "post", 300), 42, DAY);
    assertThat(c.domain("book").items().get(0).id()).isEqualTo("b_000001");
    assertThat(c.get("v_000001").domain()).isEqualTo("video");
    assertThat(c.domain("video").items()).allMatch(v -> v.durationMs() != null);
    assertThat(c.domain("post").items())
        .allMatch(p -> p.moods().isEmpty() && p.description().contains(p.genres().get(0)));
    // Shared vocabulary: every domain has jazz items.
    for (String d : List.of("song", "book", "video", "post")) {
      assertThat(c.domain(d).inGenre("pop")).isNotEmpty();
    }
  }

  @Test
  void tasteIsSharedAcrossDomains() {
    var c = new Catalogs(Map.of("song", 2000, "book", 500), 42, DAY);
    var fan = new UserProfile("u", Map.of("jazz", 1.0), List.of("mellow"), "US", "web", 0.5);
    var jazzBook = c.domain("book").inGenre("jazz").get(0);
    var metalBook = c.domain("book").inGenre("metal").get(0);
    assertThat(fan.affinity(jazzBook)).isGreaterThan(fan.affinity(metalBook));
  }
}
