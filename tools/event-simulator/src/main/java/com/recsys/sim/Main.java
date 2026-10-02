package com.recsys.sim;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Usage:
 *
 * <pre>
 *   event-simulator catalog   [--songs 50000 --books 10000 --videos 10000 --posts 20000]
 *   event-simulator simulate  [--users 5000] [--concurrency 200] [--rate 500] [--duration 10m]
 *   event-simulator freshness [--trials 5] [--genre jazz] [--domain song] [--read-domain song]
 *   event-simulator all       (catalog, then simulate)
 * common: --ingest-url http://localhost:8081 --recs-url http://localhost:8080
 *         --catalog-url http://localhost:8082 --api-key dev-key --seed 42
 * </pre>
 */
public final class Main {
  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      System.err.println(
          "commands: catalog | simulate | freshness | all   (see Main javadoc for flags)");
      System.exit(2);
    }
    Map<String, String> opt = parse(args);
    var api =
        new ApiClient(
            opt.getOrDefault("ingest-url", env("INGEST_URL", "http://localhost:8081")),
            opt.getOrDefault("recs-url", env("RECS_URL", "http://localhost:8080")),
            opt.getOrDefault("catalog-url", env("CATALOG_URL", "http://localhost:8082")),
            opt.getOrDefault("api-key", env("RECS_API_KEY", "dev-key")));
    Map<String, Integer> sizes = new LinkedHashMap<>();
    sizes.put("song", Integer.parseInt(opt.getOrDefault("songs", "50000")));
    sizes.put("book", Integer.parseInt(opt.getOrDefault("books", "10000")));
    sizes.put("video", Integer.parseInt(opt.getOrDefault("videos", "10000")));
    sizes.put("post", Integer.parseInt(opt.getOrDefault("posts", "20000")));
    sizes.values().removeIf(n -> n <= 0);
    var catalogs =
        new Catalogs(sizes, Long.parseLong(opt.getOrDefault("seed", "42")), LocalDate.now());
    switch (args[0]) {
      case "catalog" -> loadCatalog(api, catalogs);
      case "simulate" -> simulate(api, catalogs, opt);
      case "freshness" -> {
        String domain = opt.getOrDefault("domain", "song");
        new FreshnessCheck(api, catalogs)
            .run(
                Integer.parseInt(opt.getOrDefault("trials", "5")),
                opt.getOrDefault("genre", "jazz"),
                domain,
                opt.getOrDefault("read-domain", domain));
      }
      case "all" -> {
        loadCatalog(api, catalogs);
        System.out.println("Waiting 30s for embeddings to be indexed...");
        Thread.sleep(30_000);
        simulate(api, catalogs, opt);
      }
      default -> throw new IllegalArgumentException("unknown command " + args[0]);
    }
  }

  static void simulate(ApiClient api, Catalogs catalogs, Map<String, String> opt)
      throws InterruptedException {
    new Simulator(
            api,
            catalogs,
            Integer.parseInt(opt.getOrDefault("users", "5000")),
            Integer.parseInt(opt.getOrDefault("concurrency", "200")),
            Double.parseDouble(opt.getOrDefault("rate", "500")),
            duration(opt.getOrDefault("duration", "10m")))
        .run();
  }

  static void loadCatalog(ApiClient api, Catalogs catalogs) throws Exception {
    List<Map<String, Object>> all = new ArrayList<>();
    for (String d : catalogs.domains()) {
      for (Item s : catalogs.domain(d).items()) {
        Map<String, Object> m = new HashMap<>();
        m.put("itemId", s.id());
        m.put("domain", s.domain());
        m.put("title", s.title());
        m.put("artistId", s.artistId());
        m.put("artistName", s.artistName());
        m.put("genres", s.genres());
        m.put("moods", s.moods());
        m.put("durationMs", s.durationMs());
        m.put("releaseDate", s.releaseDate().toString());
        m.put("explicit", s.explicit());
        m.put("availableRegions", s.regions());
        m.put("description", s.description());
        all.add(m);
      }
    }
    AtomicInteger done = new AtomicInteger();
    try (var exec = Executors.newFixedThreadPool(4)) {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < all.size(); i += 500) {
        List<Map<String, Object>> batch = all.subList(i, Math.min(all.size(), i + 500));
        futures.add(
            exec.submit(
                () -> {
                  api.postCatalog(batch);
                  int n = done.addAndGet(batch.size());
                  if (n % 10_000 < 500) {
                    System.out.printf("catalog: %d/%d%n", n, all.size());
                  }
                  return null;
                }));
      }
      for (Future<?> f : futures) {
        f.get();
      }
    }
    System.out.printf("catalog: loaded %d items %s%n", all.size(), catalogs.domains());
  }

  static Duration duration(String s) {
    char unit = s.charAt(s.length() - 1);
    long n = Long.parseLong(s.substring(0, s.length() - 1));
    return switch (unit) {
      case 's' -> Duration.ofSeconds(n);
      case 'm' -> Duration.ofMinutes(n);
      case 'h' -> Duration.ofHours(n);
      default -> Duration.ofSeconds(Long.parseLong(s));
    };
  }

  static Map<String, String> parse(String[] args) {
    Map<String, String> m = new HashMap<>();
    for (int i = 1; i < args.length - 1; i += 2) {
      m.put(args[i].replaceFirst("^--", ""), args[i + 1]);
    }
    return m;
  }

  private static String env(String key, String def) {
    String v = System.getenv(key);
    return v == null || v.isBlank() ? def : v;
  }
}
