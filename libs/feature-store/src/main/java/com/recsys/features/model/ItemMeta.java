package com.recsys.features.model;

import java.time.LocalDate;
import java.util.List;

/** Item metadata needed at serving time (Redis {@code i:{id}:meta}, written by catalog-service). */
public record ItemMeta(
    String itemId,
    String domain,
    String title,
    String artistId,
    String artistName,
    List<String> genres,
    List<String> moods,
    Long durationMs,
    LocalDate releaseDate,
    boolean explicit,
    List<String> regions,
    long createdTs) {

  public boolean availableIn(String region) {
    return region == null || regions == null || regions.isEmpty() || regions.contains(region);
  }
}
