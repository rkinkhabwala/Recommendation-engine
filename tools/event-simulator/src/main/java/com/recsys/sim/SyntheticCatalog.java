package com.recsys.sim;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Deterministic synthetic song catalog (same seed → same catalog, so the API's static fallback list
 * and the simulator agree on ids {@code s_000001...}). Genre popularity and per-song popularity are
 * Zipf-like to produce a realistic head and long tail.
 */
public final class SyntheticCatalog {
  public static final List<String> GENRES =
      List.of(
          "pop",
          "rock",
          "hip-hop",
          "electronic",
          "indie",
          "r&b",
          "latin",
          "k-pop",
          "jazz",
          "country",
          "metal",
          "classical",
          "folk",
          "house",
          "soul",
          "punk",
          "ambient",
          "blues",
          "reggae",
          "techno");
  public static final List<String> MOODS =
      List.of(
          "happy",
          "sad",
          "energetic",
          "chill",
          "romantic",
          "dark",
          "uplifting",
          "melancholic",
          "aggressive",
          "dreamy",
          "focused",
          "party",
          "mellow",
          "nostalgic");
  private static final Map<String, List<String>> GENRE_MOODS =
      Map.ofEntries(
          Map.entry("pop", List.of("happy", "party", "romantic", "uplifting")),
          Map.entry("rock", List.of("energetic", "aggressive", "nostalgic")),
          Map.entry("hip-hop", List.of("energetic", "party", "dark")),
          Map.entry("electronic", List.of("energetic", "party", "dreamy")),
          Map.entry("indie", List.of("melancholic", "dreamy", "nostalgic")),
          Map.entry("r&b", List.of("romantic", "mellow", "chill")),
          Map.entry("latin", List.of("party", "happy", "romantic")),
          Map.entry("k-pop", List.of("happy", "energetic", "party")),
          Map.entry("jazz", List.of("mellow", "chill", "romantic")),
          Map.entry("country", List.of("nostalgic", "happy", "sad")),
          Map.entry("metal", List.of("aggressive", "dark", "energetic")),
          Map.entry("classical", List.of("focused", "melancholic", "dreamy")),
          Map.entry("folk", List.of("nostalgic", "mellow", "sad")),
          Map.entry("house", List.of("party", "energetic", "uplifting")),
          Map.entry("soul", List.of("romantic", "mellow", "uplifting")),
          Map.entry("punk", List.of("aggressive", "energetic")),
          Map.entry("ambient", List.of("chill", "focused", "dreamy")),
          Map.entry("blues", List.of("sad", "mellow", "melancholic")),
          Map.entry("reggae", List.of("chill", "happy")),
          Map.entry("techno", List.of("energetic", "dark", "focused")));
  private static final String[] ADJ = {
    "Midnight", "Golden", "Electric", "Silent", "Broken", "Neon", "Wild", "Velvet", "Lonely",
        "Burning",
    "Crystal", "Faded", "Endless", "Summer", "Frozen", "Hidden", "Crimson", "Paper", "Digital",
        "Gentle"
  };
  private static final String[] NOUN = {
    "Heart", "City", "River", "Dream", "Fire", "Sky", "Road", "Light", "Ocean", "Echo",
    "Mirror", "Garden", "Storm", "Signal", "Moon", "Shadow", "Wave", "Highway", "Letter", "Season"
  };
  private static final List<String> REGIONS = List.of("US", "GB", "DE", "IN", "BR", "KR");

  private final String domain;
  private final List<Item> songs;
  private final Map<String, Item> byId = new HashMap<>();
  private final Map<String, List<Item>> byGenre = new HashMap<>();

  public SyntheticCatalog(int size, long seed, LocalDate today) {
    this.domain = "song";
    Random r = new Random(seed);
    int artists = Math.max(20, size / 10);
    List<String[]> artistInfo = new ArrayList<>(); // id, name, primary, secondary
    for (int a = 0; a < artists; a++) {
      String primary = GENRES.get(zipfIndex(r, GENRES.size(), 0.9));
      String secondary = r.nextDouble() < 0.3 ? GENRES.get(r.nextInt(GENRES.size())) : null;
      artistInfo.add(
          new String[] {
            "a_%05d".formatted(a + 1),
            "The " + NOUN[r.nextInt(NOUN.length)] + " " + (a + 1),
            primary,
            secondary
          });
    }
    songs = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      String[] artist = artistInfo.get(zipfIndex(r, artists, 0.6));
      List<String> genres = new ArrayList<>();
      genres.add(artist[2]);
      if (artist[3] != null && !artist[3].equals(artist[2])) {
        genres.add(artist[3]);
      }
      List<String> pool = GENRE_MOODS.get(artist[2]);
      List<String> moods = new ArrayList<>();
      moods.add(pool.get(r.nextInt(pool.size())));
      String m2 =
          r.nextDouble() < 0.7
              ? pool.get(r.nextInt(pool.size()))
              : MOODS.get(r.nextInt(MOODS.size()));
      if (!moods.contains(m2)) {
        moods.add(m2);
      }
      boolean isNew = r.nextDouble() < 0.02;
      LocalDate release =
          isNew ? today.minusDays(r.nextInt(14)) : today.minusDays(14 + r.nextInt(3650));
      boolean explicit = r.nextDouble() < (artist[2].equals("hip-hop") ? 0.4 : 0.08);
      List<String> regions =
          r.nextDouble() < 0.9
              ? List.of()
              : List.of(
                  REGIONS.get(r.nextInt(REGIONS.size())), REGIONS.get(r.nextInt(REGIONS.size())));
      String title = ADJ[r.nextInt(ADJ.length)] + " " + NOUN[r.nextInt(NOUN.length)];
      double popularity = 1.0 / Math.pow(i + 1, 0.8);
      Item s =
          new Item(
              "song",
              "s_%06d".formatted(i + 1),
              title,
              artist[0],
              artist[1],
              List.copyOf(genres),
              List.copyOf(moods),
              150_000L + r.nextInt(150_000),
              release,
              explicit,
              regions.stream().distinct().toList(),
              popularity,
              null);
      songs.add(s);
      byId.put(s.id(), s);
      for (String g : s.genres()) {
        byGenre.computeIfAbsent(g, k -> new ArrayList<>()).add(s);
      }
    }
  }

  private static final String[] BOOK_NOUNS = {
    "History",
    "Guide",
    "Novel",
    "Memoir",
    "Chronicle",
    "Story",
    "Biography",
    "Handbook",
    "Journey",
    "Legacy"
  };
  private static final String[] VIDEO_FORMATS = {
    "live session",
    "documentary",
    "tutorial",
    "behind the scenes",
    "concert",
    "interview",
    "review",
    "explainer"
  };
  private static final String[] POST_OPENERS = {
    "Hot take:",
    "Just discovered",
    "Can we talk about",
    "My favourite",
    "Thread on",
    "Quick thoughts on",
    "Unpopular opinion about",
    "Weekend plans with"
  };

  /**
   * Books, videos and posts share the songs' genre vocabulary as topics, so items about "jazz" in
   * every domain are close in the (shared) embedding space. Posts and half of the books ship
   * without moods, which the LLM enrichment worker then fills in.
   */
  private SyntheticCatalog(String domain, int size, long seed, LocalDate today) {
    this.domain = domain;
    Random r = new Random(seed);
    String prefix = domain.substring(0, 1);
    int creators = Math.max(20, size / 8);
    songs = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      int c = zipfIndex(r, creators, 0.6);
      String creatorId = prefix + "c_%05d".formatted(c + 1);
      String genre = GENRES.get(new Random(seed * 31 + c).nextInt(GENRES.size()) % GENRES.size());
      String second = r.nextDouble() < 0.3 ? GENRES.get(r.nextInt(GENRES.size())) : null;
      List<String> genres =
          second == null || second.equals(genre) ? List.of(genre) : List.of(genre, second);
      List<String> pool = GENRE_MOODS.get(genre);
      String mood = pool.get(r.nextInt(pool.size()));
      String adj = ADJ[r.nextInt(ADJ.length)];
      String noun = NOUN[r.nextInt(NOUN.length)];
      String title;
      String description;
      Long duration = null;
      List<String> moods;
      String creatorName;
      switch (domain) {
        case "book" -> {
          creatorName = "Author " + (c + 1);
          title =
              "The "
                  + adj
                  + " "
                  + noun
                  + ": A "
                  + genre
                  + " "
                  + BOOK_NOUNS[r.nextInt(BOOK_NOUNS.length)];
          description =
              "A "
                  + mood
                  + " "
                  + BOOK_NOUNS[r.nextInt(BOOK_NOUNS.length)].toLowerCase()
                  + " about "
                  + genre
                  + " and the "
                  + noun.toLowerCase()
                  + " that shaped it. "
                  + (second == null ? "" : "Touches on " + second + ".");
          moods = r.nextDouble() < 0.5 ? List.of(mood) : List.of();
        }
        case "video" -> {
          creatorName = "Channel " + (c + 1);
          String format = VIDEO_FORMATS[r.nextInt(VIDEO_FORMATS.length)];
          title = adj + " " + noun + " | " + genre + " " + format;
          description =
              "A " + mood + " " + genre + " " + format + " exploring " + noun.toLowerCase() + ".";
          duration = 120_000L + r.nextInt(1_500_000);
          moods = List.of(mood);
        }
        default -> {
          creatorName = "Poster " + (c + 1);
          title = genre + " post";
          description =
              POST_OPENERS[r.nextInt(POST_OPENERS.length)]
                  + " "
                  + genre
                  + " "
                  + noun.toLowerCase()
                  + " tonight"
                  + (second == null ? "" : ", plus some " + second)
                  + ".";
          moods = List.of(); // posts arrive unlabelled; enrichment adds moods/topics/tone
        }
      }
      LocalDate release =
          r.nextDouble() < 0.03
              ? today.minusDays(r.nextInt(7))
              : today.minusDays(7 + r.nextInt(domain.equals("post") ? 60 : 3000));
      Item item =
          new Item(
              domain,
              prefix + "_%06d".formatted(i + 1),
              title,
              creatorId,
              creatorName,
              genres,
              moods,
              duration,
              release,
              false,
              List.of(),
              1.0 / Math.pow(i + 1, 0.8),
              description);
      songs.add(item);
      byId.put(item.id(), item);
      for (String g : genres) {
        byGenre.computeIfAbsent(g, k -> new ArrayList<>()).add(item);
      }
    }
  }

  public static SyntheticCatalog forDomain(String domain, int size, long seed, LocalDate today) {
    return domain.equals("song")
        ? new SyntheticCatalog(size, seed, today)
        : new SyntheticCatalog(domain, size, seed + domain.hashCode(), today);
  }

  public String domain() {
    return domain;
  }

  /** Index in [0, n) with P(k) ∝ 1/(k+1)^s. */
  static int zipfIndex(Random r, int n, double s) {
    double u = r.nextDouble();
    double norm = 0;
    for (int k = 0; k < n; k++) {
      norm += 1.0 / Math.pow(k + 1, s);
    }
    double acc = 0;
    for (int k = 0; k < n; k++) {
      acc += 1.0 / Math.pow(k + 1, s) / norm;
      if (u <= acc) {
        return k;
      }
    }
    return n - 1;
  }

  public List<Item> items() {
    return songs;
  }

  public Item get(String id) {
    return byId.get(id);
  }

  public List<Item> inGenre(String genre) {
    return byGenre.getOrDefault(genre, List.of());
  }
}
