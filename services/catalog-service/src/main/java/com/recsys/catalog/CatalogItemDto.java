package com.recsys.catalog;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Public JSON contract for catalog items. {@code artistId}/{@code artistName} = creator. */
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
    Long seq,
    Instant createdAt,
    Instant updatedAt) {

  public CatalogItemDto {
    genres = genres == null ? List.of() : List.copyOf(genres);
    moods = moods == null ? List.of() : List.copyOf(moods);
    availableRegions = availableRegions == null ? List.of() : List.copyOf(availableRegions);
    explicit = explicit != null && explicit;
  }

  public String validate() {
    if (itemId == null || !itemId.matches("^[A-Za-z0-9_.:-]{1,64}$")) {
      return "INVALID_ITEM_ID";
    }
    if (!"song".equals(domain)
        && !"book".equals(domain)
        && !"video".equals(domain)
        && !"post".equals(domain)) {
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
    return null;
  }
}
