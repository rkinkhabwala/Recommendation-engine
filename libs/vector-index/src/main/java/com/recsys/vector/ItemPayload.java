package com.recsys.vector;

import java.util.List;

/**
 * Payload stored next to each item vector. Filter fields (domain, regions, explicit, ingestedAt)
 * are payload-indexed. {@code regions} contains "ALL" when the item is available everywhere.
 */
public record ItemPayload(
    String itemId,
    String domain,
    String artistId,
    List<String> genres,
    List<String> moods,
    boolean explicit,
    List<String> regions,
    long ingestedAt,
    String contentHash,
    String indexVersion) {

  public static final String ALL_REGIONS = "ALL";

  public ItemPayload {
    genres = List.copyOf(genres);
    moods = List.copyOf(moods);
    regions = regions.isEmpty() ? List.of(ALL_REGIONS) : List.copyOf(regions);
  }
}
