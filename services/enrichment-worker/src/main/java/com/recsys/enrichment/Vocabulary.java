package com.recsys.enrichment;

import java.util.List;
import java.util.Set;

/** Closed vocabularies the LLM must choose from (enforced by schema and re-checked on output). */
public final class Vocabulary {
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
          "nostalgic",
          "calm",
          "tense",
          "hopeful",
          "playful");
  public static final List<String> TONES =
      List.of(
          "informative",
          "humorous",
          "inspirational",
          "critical",
          "reflective",
          "casual",
          "formal",
          "nostalgic",
          "urgent");
  public static final List<String> READING_LEVELS = List.of("beginner", "intermediate", "advanced");
  public static final Set<String> READING_LEVEL_DOMAINS = Set.of("book", "post");

  private Vocabulary() {}
}
