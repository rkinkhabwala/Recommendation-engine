package com.recsys.sim;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A simulated user: one topic taste shared across domains (so cross-domain signals are real),
 * moods, how often they use each domain, country, device and patience.
 */
public record UserProfile(
    String userId,
    Map<String, Double> genres,
    List<String> moods,
    Map<String, Double> domainShare,
    String country,
    String device,
    double patience) {

  private static final List<String> COUNTRIES =
      List.of("US", "US", "US", "GB", "DE", "IN", "IN", "BR", "KR");
  private static final List<String> DEVICES = List.of("ios", "android", "web", "desktop");

  public UserProfile(
      String userId,
      Map<String, Double> genres,
      List<String> moods,
      String country,
      String device,
      double patience) {
    this(userId, genres, moods, Map.of("song", 1.0), country, device, patience);
  }

  public static UserProfile random(String userId, Random r, List<String> domains) {
    var genres = new LinkedHashMap<String, Double>();
    int n = 1 + r.nextInt(3);
    while (genres.size() < n) {
      genres.put(
          SyntheticCatalog.GENRES.get(
              SyntheticCatalog.zipfIndex(r, SyntheticCatalog.GENRES.size(), 0.9)),
          1.0 / (genres.size() + 1));
    }
    var moods = new java.util.ArrayList<String>();
    for (int i = 0; i < 2; i++) {
      moods.add(SyntheticCatalog.MOODS.get(r.nextInt(SyntheticCatalog.MOODS.size())));
    }
    Map<String, Double> base = Map.of("song", 0.5, "video", 0.25, "post", 0.15, "book", 0.10);
    var share = new LinkedHashMap<String, Double>();
    for (String d : domains) {
      share.put(d, base.getOrDefault(d, 0.1) * (0.5 + r.nextDouble()));
    }
    return new UserProfile(
        userId,
        Map.copyOf(genres),
        List.copyOf(moods),
        share,
        COUNTRIES.get(r.nextInt(COUNTRIES.size())),
        DEVICES.get(r.nextInt(DEVICES.size())),
        0.3 + r.nextDouble() * 0.5);
  }

  public String pickDomain(Random r) {
    double total = domainShare.values().stream().mapToDouble(Double::doubleValue).sum();
    double u = r.nextDouble() * total;
    for (var e : domainShare.entrySet()) {
      u -= e.getValue();
      if (u <= 0) {
        return e.getKey();
      }
    }
    return domainShare.keySet().iterator().next();
  }

  /** How much this user likes an item, roughly in [0, 1.5]. */
  public double affinity(Item s) {
    if (s == null) {
      return 0;
    }
    double g = s.genres().stream().mapToDouble(x -> genres.getOrDefault(x, 0.0)).max().orElse(0);
    double m = s.moods().stream().filter(moods::contains).count() * 0.25;
    return g + m;
  }
}
