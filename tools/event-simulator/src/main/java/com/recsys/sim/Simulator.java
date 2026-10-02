package com.recsys.sim;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Simulates listeners following next-track recommendations. Each step: fetch recommendations, emit
 * impressions for the visible slots, pick a track with position bias and taste match (or search for
 * one), then play it with an outcome driven by how well it matches the user's taste: complete
 * (+like/save/replay), listen-then-skip, or early skip (+dislike/not-interested). Time is
 * compressed: a "listen" is reported immediately with its duration in {@code value}.
 */
public final class Simulator {
  private final ApiClient api;
  private final SyntheticCatalog catalog;
  private final int users;
  private final int concurrency;
  private final double stepsPerSecond;
  private final Duration duration;
  private final Stats stats = new Stats();
  private final List<UserProfile> profiles = new ArrayList<>();

  public Simulator(
      ApiClient api,
      SyntheticCatalog catalog,
      int users,
      int concurrency,
      double eventsPerSecond,
      Duration duration) {
    this.api = api;
    this.catalog = catalog;
    this.users = users;
    this.concurrency = concurrency;
    this.stepsPerSecond =
        eventsPerSecond / 7.0; // ~7 events per step (impressions + play + outcome)
    this.duration = duration;
    Random r = new Random(1234);
    for (int i = 0; i < users; i++) {
      profiles.add(UserProfile.random("sim_u%06d".formatted(i + 1), r));
    }
  }

  public void run() throws InterruptedException {
    long end = System.nanoTime() + duration.toNanos();
    AtomicBoolean running = new AtomicBoolean(true);
    long intervalNanos = (long) (1e9 / stepsPerSecond);
    var nextSlot = new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
    Map<String, Boolean> onboarded = new java.util.concurrent.ConcurrentHashMap<>();
    try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int t = 0; t < concurrency; t++) {
        exec.submit(
            () -> {
              while (running.get() && System.nanoTime() < end) {
                UserProfile u = profiles.get(ThreadLocalRandom.current().nextInt(profiles.size()));
                try {
                  if (onboarded.putIfAbsent(u.userId(), true) == null
                      && ThreadLocalRandom.current().nextDouble() < 0.1) {
                    onboard(u);
                  }
                  session(u, end, () -> pace(nextSlot, intervalNanos));
                } catch (Exception e) {
                  stats.recErrors.increment();
                }
              }
              return null;
            });
      }
      while (System.nanoTime() < end) {
        TimeUnit.SECONDS.sleep(5);
        System.out.println(stats.line());
      }
      running.set(false);
    }
    System.out.println("FINAL " + stats.line());
  }

  /** Global step pacing (token-bucket style) to hold the target event rate. */
  private static void pace(java.util.concurrent.atomic.AtomicLong nextSlot, long intervalNanos) {
    long slot = nextSlot.getAndAdd(intervalNanos);
    long wait = slot - System.nanoTime();
    if (wait < -1_000_000_000L) {
      nextSlot.set(System.nanoTime()); // fell behind by >1s: don't burst to catch up
      return;
    }
    // parkNanos may return early (spurious wake-ups, virtual threads): loop until the slot.
    while ((wait = slot - System.nanoTime()) > 0) {
      LockSupport.parkNanos(wait);
    }
  }

  private void onboard(UserProfile u) throws Exception {
    String genre = u.genres().keySet().iterator().next();
    api.onboarding(
        u.userId(),
        Map.of(
            "domain",
            "song",
            "picks",
            Map.of("genres", List.copyOf(u.genres().keySet())),
            "freeText",
            "I'm into " + genre + ", mostly " + u.moods().get(0) + " stuff"));
  }

  private void session(UserProfile u, long end, Runnable pace) throws Exception {
    var r = ThreadLocalRandom.current();
    String session = "sess-" + UUID.randomUUID();
    Song current = null;
    int steps = 5 + r.nextInt(15);
    for (int step = 0; step < steps && System.nanoTime() < end; step++) {
      pace.run();
      List<Map<String, Object>> batch = new ArrayList<>();
      Instant now = Instant.now();
      Song next = null;
      String recId = null;
      Integer pos = null;
      String variant = null;
      if (r.nextDouble() < (current == null ? 0.6 : 0.85)) {
        Map<String, String> params = new HashMap<>();
        params.put("userId", u.userId());
        params.put("domain", "song");
        params.put("context", current == null ? "home" : "next_track");
        params.put("seedItemId", current == null ? null : current.id());
        params.put("sessionId", session);
        params.put("country", u.country());
        params.put("device", u.device());
        params.put("limit", "10");
        long t0 = System.nanoTime();
        JsonNode res;
        try {
          res = api.recommendations(params);
        } catch (Exception e) {
          stats.recErrors.increment();
          continue;
        }
        stats.latency((System.nanoTime() - t0) / 1000);
        stats.recRequests.increment();
        stats
            .fallback
            .computeIfAbsent(
                res.path("fallbackLevel").asText(),
                k -> new java.util.concurrent.atomic.LongAdder())
            .increment();
        recId = res.path("recommendationId").asText(null);
        variant = res.path("variantId").asText(null);
        JsonNode items = res.path("items");
        int visible = Math.min(5, items.size());
        for (int i = 0; i < visible; i++) {
          Song s = catalog.get(items.get(i).path("itemId").asText());
          if (s != null) {
            batch.add(
                Events.event(u, session, s, "impression", null, now, recId, i, variant, null));
          }
        }
        for (int i = 0; i < visible && next == null; i++) {
          Song s = catalog.get(items.get(i).path("itemId").asText());
          double positionBias = 1.0 / (1 + i * 0.7);
          double p = 0.25 * positionBias + 0.6 * Math.min(1, u.affinity(s == null ? null : s));
          if (s != null && r.nextDouble() < p) {
            next = s;
            pos = i;
          }
        }
      }
      if (next == null) {
        next = search(u, r);
        recId = null;
        pos = null;
        batch.add(
            Events.event(
                u, session, next, "search_result_click", null, now, null, null, null, null));
      }
      boolean autoplay = recId != null && current != null;
      play(u, session, next, batch, now.plusMillis(5), recId, pos, variant, autoplay, r);
      JsonNode res = api.postEvents(batch);
      stats.eventsSent.add(res.path("accepted").asInt());
      stats.eventsRejected.add(res.path("rejected").size());
      current = next;
      Thread.sleep(50 + r.nextInt(200));
    }
  }

  private Song search(UserProfile u, Random r) {
    List<String> genres = List.copyOf(u.genres().keySet());
    List<Song> pool = catalog.inGenre(genres.get(r.nextInt(genres.size())));
    if (pool.isEmpty()) {
      return catalog.songs().get(r.nextInt(catalog.songs().size()));
    }
    // Popular songs are found more often (pool is ordered by popularity rank).
    int idx =
        Math.min(pool.size() - 1, (int) Math.floor(Math.pow(r.nextDouble(), 3) * pool.size()));
    return pool.get(idx);
  }

  private void play(
      UserProfile u,
      String session,
      Song s,
      List<Map<String, Object>> batch,
      Instant ts,
      String recId,
      Integer pos,
      String variant,
      boolean autoplay,
      Random r) {
    stats.plays.increment();
    batch.add(Events.event(u, session, s, "play_start", null, ts, recId, pos, variant, autoplay));
    double a = u.affinity(s) + r.nextGaussian() * 0.15;
    double dur = s.durationMs() / 1000.0;
    Instant end = ts.plusMillis(5);
    if (a > 0.85) {
      stats.completes.increment();
      batch.add(
          Events.event(
              u,
              session,
              s,
              "play_end",
              dur * (0.93 + r.nextDouble() * 0.07),
              end,
              recId,
              pos,
              variant,
              autoplay));
      if (r.nextDouble() < 0.12) {
        batch.add(
            Events.event(
                u, session, s, "like", null, end.plusMillis(1), recId, pos, variant, autoplay));
      }
      if (r.nextDouble() < 0.05) {
        batch.add(
            Events.event(
                u, session, s, "save", null, end.plusMillis(2), recId, pos, variant, autoplay));
      }
      if (r.nextDouble() < 0.03) {
        batch.add(
            Events.event(
                u, session, s, "replay", null, end.plusMillis(3), recId, pos, variant, autoplay));
      }
    } else if (a > 0.45 + (1 - u.patience()) * 0.2) {
      batch.add(
          Events.event(
              u, session, s, "skip", 30 + r.nextDouble() * 90, end, recId, pos, variant, autoplay));
    } else {
      stats.earlySkips.increment();
      batch.add(
          Events.event(
              u, session, s, "skip", 1 + r.nextDouble() * 8, end, recId, pos, variant, autoplay));
      double d = r.nextDouble();
      if (d < 0.05) {
        batch.add(
            Events.event(
                u, session, s, "dislike", null, end.plusMillis(1), recId, pos, variant, autoplay));
      } else if (d < 0.06) {
        batch.add(
            Events.event(
                u,
                session,
                s,
                "not_interested",
                null,
                end.plusMillis(1),
                recId,
                pos,
                variant,
                autoplay));
      }
    }
  }
}
