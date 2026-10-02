package com.recsys.catalog;

import java.util.List;

/** LLM-generated metadata, already validated by the enrichment worker (and re-checked here). */
public record Enrichment(
    int version,
    List<String> moods,
    List<String> themes,
    List<String> topics,
    String tone,
    String readingLevel) {

  public Enrichment {
    moods = moods == null ? List.of() : List.copyOf(moods);
    themes = themes == null ? List.of() : List.copyOf(themes);
    topics = topics == null ? List.of() : List.copyOf(topics);
  }

  public String validate() {
    if (version <= 0) {
      return "INVALID_VERSION";
    }
    if (moods.size() > 5 || themes.size() > 8 || topics.size() > 8) {
      return "TOO_MANY_TAGS";
    }
    for (List<String> tags : List.of(moods, themes, topics)) {
      for (String t : tags) {
        if (t == null || t.isBlank() || t.length() > 40) {
          return "INVALID_TAG";
        }
      }
    }
    if ((tone != null && tone.length() > 30)
        || (readingLevel != null && readingLevel.length() > 30)) {
      return "INVALID_LABEL";
    }
    return null;
  }
}
