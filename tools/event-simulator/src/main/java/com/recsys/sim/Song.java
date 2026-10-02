package com.recsys.sim;

import java.time.LocalDate;
import java.util.List;

public record Song(
    String id,
    String title,
    String artistId,
    String artistName,
    List<String> genres,
    List<String> moods,
    long durationMs,
    LocalDate releaseDate,
    boolean explicit,
    List<String> regions,
    double popularity) {}
