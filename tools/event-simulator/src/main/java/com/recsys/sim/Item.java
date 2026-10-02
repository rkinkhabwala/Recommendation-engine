package com.recsys.sim;

import java.time.LocalDate;
import java.util.List;

/**
 * A synthetic catalog item of any domain. {@code durationMs} is null for books and posts; {@code
 * description} is the book blurb, video description or post text (null for songs).
 */
public record Item(
    String domain,
    String id,
    String title,
    String artistId,
    String artistName,
    List<String> genres,
    List<String> moods,
    Long durationMs,
    LocalDate releaseDate,
    boolean explicit,
    List<String> regions,
    double popularity,
    String description) {}
