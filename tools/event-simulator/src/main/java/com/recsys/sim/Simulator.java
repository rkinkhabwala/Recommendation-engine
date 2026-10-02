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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * Simulates users across domains. A session picks a domain from the user's habits; each step
 * fetches recommendations, emits impressions for the visible slots, picks an item with position
 * bias and taste match (or searches), and consumes it with a domain-specific outcome driven by how
 * well it matches the user's (cross-domain) taste:
 *
 * <ul>
 *   <li>song: complete (+like/save/replay), listen-then-skip, early skip (+dislike)
 *   <li>video: complete (+like/share), partial watch, abandon (+dislike)
 *   <li>book: open (click) + dwell, then save / rate 4-5, or a short dwell and occasional low
 *       rating
 *   <li>post: dwell on every visible post (scroll-past for poor matches), likes/comments/shares
 * </ul>
 *
 * Time is compressed: consumption is reported immediately with its duration in {@code value}.
 */
public final class Simulator {
  private final ApiClient api;
  private final Catalogs catalogs;
  private final int concurrency;
  private final double stepsPerSecond;
  private final Duration duration;
  private final Stats stats = new Stats();
  private final List<UserProfile> profiles = new ArrayList<>();
  private final Map<String, LongAdder> domainSteps = new ConcurrentHashMap<>();

  public Simulator(
      ApiClient api,
      Catalogs catalogs,
      int users,
      int concurrency,
      double eventsPerSecond,
      Duration duration) {
    this.api = api;
    this.catalogs = catalogs;
    this.concurrency = concurrency;
    this.stepsPerSecond =
        eventsPerSecond / 7.0; // ~7 events per step (impressions + consume + outcome)
    this.duration = duration;
    Random r = new Random(1234);
    for (int i = 0; i < users; i++) {
      profiles.add(UserProfile.random("sim_u%06d".formatted(i + 1), r, catalogs.domains()));
    }
  }

  public void run() throws InterruptedException {
    long end = System.nanoTime() + duration.toNanos();
    AtomicBoolean running = new AtomicBoolean(true);
    long intervalNanos = (long) (1e9 / stepsPerSecond);
    var nextSlot = new AtomicLong(System.nanoTime());
    Map<String, Boolean> onboarded = new ConcurrentHashMap<>();
    try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int t = 0; t < concurrency; t++) {
        exec.submit(
            () -> {
              while (running.get() && System.nanoTime() < end) {
                UserProfile u = profiles.get(ThreadLocalRandom.current().nextInt(profiles.size()));
                try {
                  String domain = u.pickDomain(ThreadLocalRandom.current());
                  if (onboarded.putIfAbsent(u.userId() + domain, true) == null
                      && ThreadLocalRandom.current().nextDouble() < 0.1) {
                    onboard(u, domain);
                  }
                  session(u, domain, end, () -> pace(nextSlot, intervalNanos));
                } catch (Exception e) {
                  stats.recErrors.increment();
                  stats.error(e);
                }
              }
              return null;
            });
      }
      while (System.nanoTime() < end) {
        TimeUnit.SECONDS.sleep(5);
        System.out.println(stats.line() + " steps=" + domainSteps);
      }
      running.set(false);
    }
    System.out.println("FINAL " + stats.line() + " steps=" + domainSteps);
    stats.errors.forEach((k, v) -> System.out.println("ERROR x" + v + " " + k));
  }

  /** Global step pacing (token-bucket style) to hold the target event rate. */
  private static void pace(AtomicLong nextSlot, long intervalNanos) {
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

  private void onboard(UserProfile u, String domain) throws Exception {
    String genre = u.genres().keySet().iterator().next();
    api.onboarding(
        u.userId(),
        Map.of(
            "domain",
            domain,
            "picks",
            Map.of("genres", List.copyOf(u.genres().keySet())),
            "freeText",
            "I'm into " + genre + ", mostly " + u.moods().get(0) + " stuff"));
  }

  private void session(UserProfile u, String domain, long end, Runnable pace) throws Exception {
    var r = ThreadLocalRandom.current();
    String session = "sess-" + UUID.randomUUID();
    Item current = null;
    int steps = 3 + r.nextInt(domain.equals("song") ? 15 : 8);
    for (int step = 0; step < steps && System.nanoTime() < end; step++) {
      pace.run();
      domainSteps.computeIfAbsent(domain, k -> new LongAdder()).increment();
      List<Map<String, Object>> batch = new ArrayList<>();
      Instant now = Instant.now();
      Item next = null;
      String recId = null;
      Integer pos = null;
      String variant = null;
      List<Item> visible = new ArrayList<>();
      if (r.nextDouble() < (current == null ? 0.7 : 0.85)) {
        Map<String, String> params = new HashMap<>();
        params.put("userId", u.userId());
        params.put("domain", domain);
        params.put(
            "context",
            current == null ? (domain.equals("post") ? "feed" : "home") : Events.surface(domain));
        params.put("seedItemId", current == null || domain.equals("post") ? null : current.id());
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
          stats.error(e);
          continue;
        }
        stats.latency((System.nanoTime() - t0) / 1000);
        stats.recRequests.increment();
        stats
            .fallback
            .computeIfAbsent(res.path("fallbackLevel").asText(), k -> new LongAdder())
            .increment();
        if (!res.path("items").isEmpty()
            && !res.path("items").get(0).path("explanation").isNull()) {
          stats.explained.increment();
        }
        recId = res.path("recommendationId").asText(null);
        variant = res.path("variantId").asText(null);
        JsonNode items = res.path("items");
        for (int i = 0; i < Math.min(5, items.size()); i++) {
          Item s = catalogs.get(items.get(i).path("itemId").asText());
          if (s != null) {
            visible.add(s);
            batch.add(
                Events.event(u, session, s, "impression", null, now, recId, i, variant, null));
          }
        }
        if (domain.equals("post")) {
          // Feeds: every visible post gets a dwell; no single "pick".
          for (int i = 0; i < visible.size(); i++) {
            feed(u, session, visible.get(i), batch, now.plusMillis(5 + i), recId, i, variant, r);
          }
          send(batch);
          current = visible.isEmpty() ? current : visible.get(0);
          Thread.sleep(50 + r.nextInt(200));
          continue;
        }
        for (int i = 0; i < visible.size() && next == null; i++) {
          double positionBias = 1.0 / (1 + i * 0.7);
          double p = 0.25 * positionBias + 0.6 * Math.min(1, u.affinity(visible.get(i)));
          if (r.nextDouble() < p) {
            next = visible.get(i);
            pos = i;
          }
        }
      }
      if (next == null) {
        next = search(u, domain, r);
        recId = null;
        pos = null;
        variant = null;
        batch.add(
            Events.event(
                u, session, next, "search_result_click", null, now, null, null, null, null));
      }
      boolean autoplay = recId != null && current != null;
      Instant ts = now.plusMillis(5);
      switch (domain) {
        case "video" -> watch(u, session, next, batch, ts, recId, pos, variant, autoplay, r);
        case "book" -> read(u, session, next, batch, ts, recId, pos, variant, r);
        case "post" -> feed(u, session, next, batch, ts, recId, pos, variant, r);
        default -> play(u, session, next, batch, ts, recId, pos, variant, autoplay, r);
      }
      send(batch);
      current = next;
      Thread.sleep(50 + r.nextInt(200));
    }
  }

  private void send(List<Map<String, Object>> batch) throws Exception {
    if (batch.isEmpty()) {
      return;
    }
    JsonNode res = api.postEvents(batch);
    stats.eventsSent.add(res.path("accepted").asInt());
    stats.eventsRejected.add(res.path("rejected").size());
  }

  private Item search(UserProfile u, String domain, Random r) {
    SyntheticCatalog catalog = catalogs.domain(domain);
    List<String> genres = List.copyOf(u.genres().keySet());
    List<Item> pool = catalog.inGenre(genres.get(r.nextInt(genres.size())));
    if (pool.isEmpty()) {
      return catalog.items().get(r.nextInt(catalog.items().size()));
    }
    // Popular items are found more often (pool is ordered by popularity rank).
    return pool.get(
        Math.min(pool.size() - 1, (int) Math.floor(Math.pow(r.nextDouble(), 3) * pool.size())));
  }

  private Map<String, Object> ev(
      UserProfile u,
      String session,
      Item s,
      String type,
      Double v,
      Instant ts,
      String recId,
      Integer pos,
      String variant,
      Boolean autoplay) {
    return Events.event(u, session, s, type, v, ts, recId, pos, variant, autoplay);
  }

  private void play(
      UserProfile u,
      String session,
      Item s,
      List<Map<String, Object>> batch,
      Instant ts,
      String recId,
      Integer pos,
      String variant,
      boolean autoplay,
      Random r) {
    stats.plays.increment();
    batch.add(ev(u, session, s, "play_start", null, ts, recId, pos, variant, autoplay));
    double a = u.affinity(s) + r.nextGaussian() * 0.15;
    double dur = s.durationMs() / 1000.0;
    Instant end = ts.plusMillis(5);
    if (a > 0.85) {
      stats.completes.increment();
      batch.add(
          ev(
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
      maybe(
          batch,
          r,
          0.12,
          ev(u, session, s, "like", null, end.plusMillis(1), recId, pos, variant, autoplay));
      maybe(
          batch,
          r,
          0.05,
          ev(u, session, s, "save", null, end.plusMillis(2), recId, pos, variant, autoplay));
      maybe(
          batch,
          r,
          0.03,
          ev(u, session, s, "replay", null, end.plusMillis(3), recId, pos, variant, autoplay));
    } else if (a > 0.45 + (1 - u.patience()) * 0.2) {
      batch.add(
          ev(u, session, s, "skip", 30 + r.nextDouble() * 90, end, recId, pos, variant, autoplay));
    } else {
      stats.earlySkips.increment();
      batch.add(
          ev(u, session, s, "skip", 1 + r.nextDouble() * 8, end, recId, pos, variant, autoplay));
      double d = r.nextDouble();
      if (d < 0.05) {
        batch.add(
            ev(u, session, s, "dislike", null, end.plusMillis(1), recId, pos, variant, autoplay));
      } else if (d < 0.06) {
        batch.add(
            ev(
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

  private void watch(
      UserProfile u,
      String session,
      Item s,
      List<Map<String, Object>> batch,
      Instant ts,
      String recId,
      Integer pos,
      String variant,
      boolean autoplay,
      Random r) {
    stats.plays.increment();
    batch.add(ev(u, session, s, "play_start", null, ts, recId, pos, variant, autoplay));
    double a = u.affinity(s) + r.nextGaussian() * 0.15;
    double dur = s.durationMs() / 1000.0;
    Instant end = ts.plusMillis(5);
    if (a > 0.85) {
      stats.completes.increment();
      batch.add(
          ev(
              u,
              session,
              s,
              "play_end",
              dur * (0.92 + r.nextDouble() * 0.08),
              end,
              recId,
              pos,
              variant,
              autoplay));
      maybe(
          batch,
          r,
          0.15,
          ev(u, session, s, "like", null, end.plusMillis(1), recId, pos, variant, autoplay));
      maybe(
          batch,
          r,
          0.04,
          ev(u, session, s, "share", null, end.plusMillis(2), recId, pos, variant, autoplay));
    } else if (a > 0.45) {
      batch.add(
          ev(
              u,
              session,
              s,
              "play_end",
              dur * (0.3 + r.nextDouble() * 0.3),
              end,
              recId,
              pos,
              variant,
              autoplay));
    } else {
      stats.earlySkips.increment();
      batch.add(
          ev(
              u,
              session,
              s,
              "play_end",
              dur * r.nextDouble() * 0.08,
              end,
              recId,
              pos,
              variant,
              autoplay));
      maybe(
          batch,
          r,
          0.05,
          ev(u, session, s, "dislike", null, end.plusMillis(1), recId, pos, variant, autoplay));
    }
  }

  private void read(
      UserProfile u,
      String session,
      Item s,
      List<Map<String, Object>> batch,
      Instant ts,
      String recId,
      Integer pos,
      String variant,
      Random r) {
    stats.plays.increment();
    batch.add(ev(u, session, s, "click", null, ts, recId, pos, variant, null));
    double a = u.affinity(s) + r.nextGaussian() * 0.15;
    Instant end = ts.plusMillis(5);
    if (a > 0.85) {
      stats.completes.increment();
      batch.add(
          ev(u, session, s, "dwell", 25 + r.nextDouble() * 60, end, recId, pos, variant, null));
      maybe(
          batch,
          r,
          0.4,
          ev(u, session, s, "save", null, end.plusMillis(1), recId, pos, variant, null));
      maybe(
          batch,
          r,
          0.3,
          ev(
              u,
              session,
              s,
              "rate",
              (double) (4 + r.nextInt(2)),
              end.plusMillis(2),
              recId,
              pos,
              variant,
              null));
    } else if (a > 0.45) {
      batch.add(
          ev(u, session, s, "dwell", 8 + r.nextDouble() * 12, end, recId, pos, variant, null));
    } else {
      stats.earlySkips.increment();
      batch.add(ev(u, session, s, "dwell", 1 + r.nextDouble() * 4, end, recId, pos, variant, null));
      maybe(
          batch,
          r,
          0.1,
          ev(
              u,
              session,
              s,
              "rate",
              (double) (1 + r.nextInt(2)),
              end.plusMillis(1),
              recId,
              pos,
              variant,
              null));
      maybe(
          batch,
          r,
          0.03,
          ev(u, session, s, "not_interested", null, end.plusMillis(2), recId, pos, variant, null));
    }
  }

  private void feed(
      UserProfile u,
      String session,
      Item s,
      List<Map<String, Object>> batch,
      Instant ts,
      String recId,
      Integer pos,
      String variant,
      Random r) {
    double a = u.affinity(s) + r.nextGaussian() * 0.15;
    if (a > 0.85) {
      stats.plays.increment();
      stats.completes.increment();
      batch.add(ev(u, session, s, "dwell", 6 + r.nextDouble() * 14, ts, recId, pos, variant, null));
      maybe(
          batch,
          r,
          0.3,
          ev(u, session, s, "like", null, ts.plusMillis(1), recId, pos, variant, null));
      maybe(
          batch,
          r,
          0.08,
          ev(u, session, s, "comment", null, ts.plusMillis(2), recId, pos, variant, null));
      maybe(
          batch,
          r,
          0.05,
          ev(u, session, s, "share", null, ts.plusMillis(3), recId, pos, variant, null));
    } else if (a > 0.45) {
      batch.add(ev(u, session, s, "dwell", 2 + r.nextDouble() * 3, ts, recId, pos, variant, null));
    } else {
      batch.add(
          ev(u, session, s, "dwell", 0.2 + r.nextDouble() * 0.7, ts, recId, pos, variant, null));
      maybe(
          batch,
          r,
          0.02,
          ev(u, session, s, "not_interested", null, ts.plusMillis(1), recId, pos, variant, null));
    }
  }

  private static void maybe(
      List<Map<String, Object>> batch, Random r, double p, Map<String, Object> event) {
    if (r.nextDouble() < p) {
      batch.add(event);
    }
  }
}
