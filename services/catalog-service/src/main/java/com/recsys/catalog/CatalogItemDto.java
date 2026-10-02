package com.recsys.catalog;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Public JSON contract for catalog items. {@code artistId}/{@code artistName} = creator (artist,
 * author, channel, poster). {@code description} is the book blurb, video description or post text.
 * Enrichment fields are read-only here (set via the enrichment endpoint); {@code moods} returned
 * are curated moods, or enriched moods when none were curated.
 */
public record CatalogItemDto(
    String itemId,
    String domain,
    String title,
    String artistId,
    String artistName,
    List<String> genres,
    List<String> moods,
    Long durationMs,
    LocalDate releaseDate,
    Boolean explicit,
    List<String> availableRegions,
    String description,
    String transcriptSummary,
    List<String> themes,
    List<String> topics,
    String tone,
    String readingLevel,
    Integer enrichmentVersion,
    Long seq,
    Instant createdAt,
    Instant updatedAt) {

  static final Set<String> DOMAINS = Set.of("song", "book", "video", "post");

  public CatalogItemDto {
    genres = genres == null ? List.of() : List.copyOf(genres);
    moods = moods == null ? List.of() : List.copyOf(moods);
    availableRegions = availableRegions == null ? List.of() : List.copyOf(availableRegions);
    themes = themes == null ? List.of() : List.copyOf(themes);
    topics = topics == null ? List.of() : List.copyOf(topics);
    explicit = explicit != null && explicit;
  }

  public String validate() {
    if (itemId == null || !itemId.matches("^[A-Za-z0-9_.:-]{1,64}$")) {
      return "INVALID_ITEM_ID";
    }
    if (!DOMAINS.contains(domain)) {
      return "INVALID_DOMAIN";
    }
    if (title == null || title.isBlank() || title.length() > 500) {
      return "INVALID_TITLE";
    }
    if (artistId == null || !artistId.matches("^[A-Za-z0-9_.:-]{1,64}$") || artistName == null) {
      return "INVALID_ARTIST";
    }
    if (genres.size() > 20 || moods.size() > 20) {
      return "TOO_MANY_TAGS";
    }
    if (description != null && description.length() > 10_000) {
      return "DESCRIPTION_TOO_LONG";
    }
    if (transcriptSummary != null && transcriptSummary.length() > 4_000) {
      return "TRANSCRIPT_SUMMARY_TOO_LONG";
    }
    return null;
  }
}
