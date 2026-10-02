package com.recsys.sim;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Measures end-to-end freshness the way a user experiences it: a brand-new user strongly engages
 * with a few tracks of one genre, then we poll recommendations until most of the list reflects that
 * genre. Target: &lt; 5 s (docs/architecture.md §1).
 */
public final class FreshnessCheck {
  private final ApiClient api;
  private final SyntheticCatalog catalog;

  public FreshnessCheck(ApiClient api, SyntheticCatalog catalog) {
    this.api = api;
    this.catalog = catalog;
  }

  public void run(int trials, String genre) throws Exception {
    List<Double> seconds = new ArrayList<>();
    for (int t = 0; t < trials; t++) {
      String userId = "fresh_" + System.currentTimeMillis() + "_" + t;
      var user = new UserProfile(userId, Map.of(genre, 1.0), List.of(), "US", "web", 0.5);
      String session = "fresh-sess-" + t;
      List<Song> picks = catalog.inGenre(genre).subList(t * 3, t * 3 + 3);
      List<Map<String, Object>> batch = new ArrayList<>();
      Instant now = Instant.now();
      for (Song s : picks) {
        batch.add(
            Events.event(
                user,
                session,
                s,
                "play_end",
                s.durationMs() / 1000.0,
                now,
                null,
                null,
                null,
                false));
        batch.add(
            Events.event(
                user, session, s, "like", null, now.plusMillis(1), null, null, null, false));
      }
      long t0 = System.nanoTime();
      api.postEvents(batch);
      double share = 0;
      while ((System.nanoTime() - t0) / 1e9 < 30) {
        JsonNode res =
            api.recommendations(
                Map.of("userId", userId, "domain", "song", "limit", "10", "country", "US"));
        int match = 0;
        int n = 0;
        for (JsonNode item : res.path("items")) {
          Song s = catalog.get(item.path("itemId").asText());
          n++;
          if (s != null && s.genres().contains(genre)) {
            match++;
          }
        }
        share = n == 0 ? 0 : (double) match / n;
        if (share >= 0.5) {
          break;
        }
        Thread.sleep(100);
      }
      double elapsed = (System.nanoTime() - t0) / 1e9;
      seconds.add(elapsed);
      System.out.printf(
          "trial %d: %.2fs until %.0f%% of top-10 were %s%n", t + 1, elapsed, share * 100, genre);
    }
    Collections.sort(seconds);
    System.out.printf(
        "freshness: median=%.2fs max=%.2fs target<5s -> %s%n",
        seconds.get(seconds.size() / 2),
        seconds.get(seconds.size() - 1),
        seconds.get(seconds.size() - 1) < 5 ? "PASS" : "FAIL");
  }
}
