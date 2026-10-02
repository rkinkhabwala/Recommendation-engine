package com.recsys.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.recsys.openai.ChatClient.StructuredRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Deterministic stand-ins for the LLM (mode=mock): keyword lexicons for enrichment, templates for
 * explanations. Outputs satisfy the same schemas, so the whole pipeline is exercised offline.
 */
final class MockLlm {
  private static final Map<String, String> MOOD_WORDS = new LinkedHashMap<>();
  private static final Map<String, String> TONE_WORDS = new LinkedHashMap<>();
  private static final Set<String> STOP =
      Set.of(
          "about",
          "after",
          "their",
          "there",
          "these",
          "which",
          "while",
          "where",
          "would",
          "could",
          "every",
          "under",
          "other",
          "genres",
          "domain",
          "title",
          "creator",
          "description",
          "curated",
          "moods",
          "summary",
          "transcript",
          "with",
          "from",
          "into",
          "this",
          "that",
          "your");

  static {
    for (String[] kv :
        new String[][] {
          {"night", "mellow"},
          {"midnight", "mellow"},
          {"love", "romantic"},
          {"heart", "romantic"},
          {"party", "party"},
          {"dance", "party"},
          {"storm", "dark"},
          {"shadow", "dark"},
          {"summer", "happy"},
          {"golden", "uplifting"},
          {"rise", "hopeful"},
          {"broken", "melancholic"},
          {"lonely", "sad"},
          {"rain", "melancholic"},
          {"focus", "focused"},
          {"study", "focused"},
          {"calm", "calm"},
          {"quiet", "calm"},
          {"fire", "energetic"},
          {"wild", "energetic"},
          {"dream", "dreamy"},
          {"funny", "playful"},
          {"memory", "nostalgic"},
          {"classic", "nostalgic"},
          {"war", "tense"}
        }) {
      MOOD_WORDS.put(kv[0], kv[1]);
    }
    for (String[] kv :
        new String[][] {
          {"guide", "informative"},
          {"how", "informative"},
          {"history", "informative"},
          {"funny", "humorous"},
          {"joke", "humorous"},
          {"inspire", "inspirational"},
          {"review", "critical"},
          {"memory", "nostalgic"},
          {"thoughts", "reflective"},
          {"breaking", "urgent"}
        }) {
      TONE_WORDS.put(kv[0], kv[1]);
    }
  }

  private MockLlm() {}

  static Map<String, Function<StructuredRequest, JsonNode>> handlers() {
    return Map.of(
        EnrichmentSchema.ENRICHMENT,
        MockLlm::enrich,
        EnrichmentSchema.EXPLANATION,
        MockLlm::explain);
  }

  static JsonNode enrich(StructuredRequest r) {
    Map<String, String> fields = fields(r.user());
    String domain = fields.getOrDefault("domain", "song");
    String text =
        (fields.getOrDefault("title", "")
                + " "
                + fields.getOrDefault("description", "")
                + " "
                + fields.getOrDefault("transcript summary", ""))
            .toLowerCase(Locale.ROOT);
    List<String> words = words(text);
    LinkedHashSet<String> moods = new LinkedHashSet<>();
    for (String w : words) {
      String m = MOOD_WORDS.get(w);
      if (m != null && moods.size() < 3) {
        moods.add(m);
      }
    }
    if (moods.isEmpty()) {
      moods.add("calm");
    }
    List<String> content =
        words.stream().filter(w -> w.length() >= 5 && !STOP.contains(w)).distinct().toList();
    List<String> genres = List.of(fields.getOrDefault("genres", "").split(",\\s*"));
    LinkedHashSet<String> topics = new LinkedHashSet<>();
    genres.stream().filter(g -> !g.isBlank()).limit(3).forEach(topics::add);
    content.stream().limit(2).forEach(topics::add);
    String tone = null;
    for (String w : words) {
      if (TONE_WORDS.containsKey(w)) {
        tone = TONE_WORDS.get(w);
        break;
      }
    }
    String level = null;
    if (Vocabulary.READING_LEVEL_DOMAINS.contains(domain)) {
      double avg = words.stream().mapToInt(String::length).average().orElse(5);
      level = avg > 6.5 ? "advanced" : avg > 5 ? "intermediate" : "beginner";
    }
    ObjectNode out = EnrichmentSchema.JSON.createObjectNode();
    moods.forEach(out.putArray("moods")::add);
    content.stream().limit(3).forEach(out.putArray("themes")::add);
    if (!out.has("themes")) {
      out.putArray("themes");
    }
    topics.stream().limit(5).forEach(out.putArray("topics")::add);
    if (!out.has("topics")) {
      out.putArray("topics");
    }
    out.put("tone", tone);
    out.put("reading_level", level);
    return out;
  }

  static JsonNode explain(StructuredRequest r) {
    Map<String, String> f = fields(r.user());
    String[] item =
        f.getOrDefault("item", "this | by someone | genres: | moods: ").split("\\s*\\|\\s*");
    String title = item[0];
    String genre = first(item.length > 2 ? item[2].replace("genres:", "") : "");
    String mood = first(item.length > 3 ? item[3].replace("moods:", "") : "");
    String seed = f.containsKey("because of") ? f.get("because of").split("\\s*\\|\\s*")[0] : null;
    String text =
        switch (f.getOrDefault("reason", "")) {
          case "LISTENED_TOGETHER" -> "People who enjoy " + seed + " often pick " + title + " too.";
          case "OFTEN_PLAYED_NEXT" -> title + " is a natural next step after " + seed + ".";
          case "SIMILAR_TO_RECENT" ->
              title + " matches the " + join(mood, genre) + " vibe you are into right now.";
          case "SIMILAR_TO_TASTE" ->
              title + " fits your long-standing taste for " + join(mood, genre) + ".";
          case "CROSS_DOMAIN" -> "Your love of " + genre + " elsewhere points to " + title + ".";
          case "TRENDING", "POPULAR_IN_REGION" ->
              title + " is catching on with listeners near you.";
          case "NEW_FOR_YOU" -> title + " is fresh and close to what you like.";
          case "ONBOARDING_MATCH" ->
              title + " lines up with the " + genre + " you told us you enjoy.";
          default -> title + " could be a good fit.";
        };
    ObjectNode out = EnrichmentSchema.JSON.createObjectNode();
    out.put("text", text.replace("null", "this").replaceAll("\\s+", " "));
    return out;
  }

  private static String join(String mood, String genre) {
    return (mood.isBlank() ? "" : mood + " ") + (genre.isBlank() ? "" : genre);
  }

  private static String first(String csv) {
    String[] p = csv.strip().split(",\\s*");
    return p.length == 0 ? "" : p[0].strip();
  }

  private static Map<String, String> fields(String prompt) {
    Map<String, String> m = new LinkedHashMap<>();
    for (String line : prompt.split("\n")) {
      int i = line.indexOf(':');
      if (i > 0) {
        m.put(line.substring(0, i).strip(), line.substring(i + 1).strip());
      }
    }
    return m;
  }

  private static List<String> words(String text) {
    List<String> out = new ArrayList<>();
    for (String w : text.split("[^\\p{L}]+")) {
      if (!w.isBlank()) {
        out.add(w);
      }
    }
    return out;
  }
}
