package com.recsys.sim;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Measures end-to-end freshness the way a user experiences it: a brand-new user strongly engages
 * with a few items of one genre in a domain, then we poll recommendations until most of the list
 * reflects that genre. Target: &lt; 5 s (docs/architecture.md §1). With {@code --cross-domain} the
 * engagement happens in one domain and recommendations are read from another.
 */
public final class FreshnessCheck {
  private static final java.util.Set<String> NOT_PERSONAL =
      java.util.Set.of("TRENDING", "POPULAR_IN_REGION", "POPULAR_FALLBACK", "NEW_FOR_YOU");
  private final ApiClient api;
  private final Catalogs catalogs;

  public FreshnessCheck(ApiClient api, Catalogs catalogs) {
    this.api = api;
    this.catalogs = catalogs;
  }

  public void run(int trials, String genre, String domain, String readDomain) throws Exception {
    List<Double> seconds = new ArrayList<>();
    List<Double> signals = new ArrayList<>();
    for (int t = 0; t < trials; t++) {
      String userId = "fresh_" + System.currentTimeMillis() + "_" + t;
      var user = new UserProfile(userId, Map.of(genre, 1.0), List.of(), "US", "web", 0.5);
      String session = "fresh-sess-" + t;
      List<Item> picks = catalogs.domain(domain).inGenre(genre).subList(t * 3, t * 3 + 3);
      List<Map<String, Object>> batch = new ArrayList<>();
      Instant now = Instant.now();
      for (Item s : picks) {
        switch (domain) {
          case "book" -> {
            batch.add(Events.event(user, session, s, "rate", 5.0, now, null, null, null, null));
            batch.add(
                Events.event(
                    user, session, s, "save", null, now.plusMillis(1), null, null, null, null));
          }
          case "post" -> {
            batch.add(Events.event(user, session, s, "dwell", 15.0, now, null, null, null, null));
            batch.add(
                Events.event(
                    user, session, s, "comment", null, now.plusMillis(1), null, null, null, null));
          }
          default -> {
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
        }
      }
      long t0 = System.nanoTime();
      api.postEvents(batch);
      double share = 0;
      double firstSignal = -1;
      while ((System.nanoTime() - t0) / 1e9 < 30) {
        JsonNode res =
            api.recommendations(
                Map.of("userId", userId, "domain", readDomain, "limit", "10", "country", "US"));
        int match = 0;
        int n = 0;
        for (JsonNode item : res.path("items")) {
          Item s = catalogs.get(item.path("itemId").asText());
          n++;
          if (s != null && s.genres().contains(genre)) {
            match++;
          }
        }
        share = n == 0 ? 0 : (double) match / n;
        if (firstSignal < 0) {
          for (JsonNode item : res.path("items")) {
            String reason = item.path("reasonCode").asText();
            // Exploration (NEW_FOR_YOU) reaches cold users too, so it does not count.
            if (!NOT_PERSONAL.contains(reason)) {
              firstSignal =
                  (System.nanoTime() - t0) / 1e9; // personalization (incl. CROSS_DOMAIN) kicked in
              break;
            }
          }
        }
        if (share >= 0.5) {
          break;
        }
        Thread.sleep(100);
      }
      double elapsed = (System.nanoTime() - t0) / 1e9;
      seconds.add(elapsed);
      signals.add(firstSignal < 0 ? 30.0 : firstSignal);
      System.out.printf(
          "trial %d: personalized after %.2fs; %.2fs until %.0f%% of top-10 %ss were %s (engaged with %ss)%n",
          t + 1, firstSignal, elapsed, share * 100, readDomain, genre, domain);
    }
    Collections.sort(seconds);
    Collections.sort(signals);
    System.out.printf(
        "personalization latency: median=%.2fs max=%.2fs target<5s -> %s%n",
        signals.get(signals.size() / 2),
        signals.get(signals.size() - 1),
        signals.get(signals.size() - 1) < 5 ? "PASS" : "FAIL");
    System.out.printf(
        "relevance (≥50%% on-genre): median=%.2fs max=%.2fs within 5s -> %s%n",
        seconds.get(seconds.size() / 2),
        seconds.get(seconds.size() - 1),
        seconds.get(seconds.size() - 1) < 5 ? "PASS" : "FAIL");
  }
}
