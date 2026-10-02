package com.recsys.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Re-validates LLM output before anything is stored (defence in depth beyond the strict schema):
 * closed vocabularies, tag hygiene (lowercase, length, no URLs/emails/handles), size caps.
 */
final class EnrichmentValidator {
  private static final Pattern TAG =
      Pattern.compile("^[\\p{L}\\p{N}][\\p{L}\\p{N} '&-]{0,38}[\\p{L}\\p{N}]$");
  private static final Pattern FORBIDDEN = Pattern.compile("(?i)(https?://|www\\.|@|\\.com\\b)");

  /** Validated enrichment ready for the catalog API. */
  record Valid(
      int version,
      List<String> moods,
      List<String> themes,
      List<String> topics,
      String tone,
      String readingLevel) {}

  private EnrichmentValidator() {}

  /**
   * @return null when the response is unusable (missing required fields)
   */
  static Valid validate(JsonNode json, int version, String domain) {
    if (json == null || !json.has("moods") || !json.has("themes") || !json.has("topics")) {
      return null;
    }
    List<String> moods =
        tags(json.get("moods"), 3).stream().filter(Vocabulary.MOODS::contains).toList();
    String tone = label(json.get("tone"), Vocabulary.TONES);
    String level =
        Vocabulary.READING_LEVEL_DOMAINS.contains(domain)
            ? label(json.get("reading_level"), Vocabulary.READING_LEVELS)
            : null;
    return new Valid(
        version, moods, tags(json.get("themes"), 5), tags(json.get("topics"), 5), tone, level);
  }

  private static List<String> tags(JsonNode arr, int max) {
    LinkedHashSet<String> out = new LinkedHashSet<>();
    if (arr != null && arr.isArray()) {
      for (JsonNode n : arr) {
        String t = n.asText("").strip().toLowerCase(Locale.ROOT);
        if (TAG.matcher(t).matches() && !FORBIDDEN.matcher(t).find()) {
          out.add(t);
        }
        if (out.size() >= max) {
          break;
        }
      }
    }
    return new ArrayList<>(out);
  }

  private static String label(JsonNode n, List<String> allowed) {
    if (n == null || n.isNull()) {
      return null;
    }
    String v = n.asText().strip().toLowerCase(Locale.ROOT);
    return allowed.contains(v) ? v : null;
  }
}
