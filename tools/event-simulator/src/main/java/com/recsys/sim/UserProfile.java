package com.recsys.sim;

import java.util.List;
import java.util.Map;
import java.util.Random;

/** A simulated listener: weighted genre/mood taste, country, device, patience. */
public record UserProfile(
    String userId,
    Map<String, Double> genres,
    List<String> moods,
    String country,
    String device,
    double patience) {

  private static final List<String> COUNTRIES =
      List.of("US", "US", "US", "GB", "DE", "IN", "IN", "BR", "KR");
  private static final List<String> DEVICES = List.of("ios", "android", "web", "desktop");

  public static UserProfile random(String userId, Random r) {
    var genres = new java.util.LinkedHashMap<String, Double>();
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
    return new UserProfile(
        userId,
        Map.copyOf(genres),
        List.copyOf(moods),
        COUNTRIES.get(r.nextInt(COUNTRIES.size())),
        DEVICES.get(r.nextInt(DEVICES.size())),
        0.3 + r.nextDouble() * 0.5);
  }

  /** How much this user likes a song, roughly in [0, 1.5]. */
  public double affinity(Song s) {
    if (s == null) {
      return 0;
    }
    double g = s.genres().stream().mapToDouble(x -> genres.getOrDefault(x, 0.0)).max().orElse(0);
    double m = s.moods().stream().filter(moods::contains).count() * 0.25;
    return g + m;
  }
}
