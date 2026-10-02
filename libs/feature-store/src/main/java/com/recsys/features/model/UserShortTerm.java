package com.recsys.features.model;

import java.util.List;
import java.util.Map;

/**
 * Real-time per-domain user features (Redis {@code u:{id}:st:<domain>}), rewritten on every event.
 *
 * @param vector unit-length short-term taste vector, float16 little endian; null if no evidence
 * @param indexVersion the embedding space {@code vector} lives in
 * @param consumed itemId → last consumption time (epoch ms), kept for the domain's consumed window
 *     (songs: hours, because they are replayable; books/videos/posts: weeks to a year)
 * @param suppressedArtists artistId → suppressed-until (epoch ms) after "not interested"
 * @param sessionGenres genre distribution of the current session ("session intent")
 */
public record UserShortTerm(
    String userId,
    String domain,
    String indexVersion,
    byte[] vector,
    List<RecentInteraction> recent,
    Map<String, Double> artistAffinity,
    Map<String, Double> genreAffinity,
    Map<String, Double> moodAffinity,
    Map<String, Long> consumed,
    List<String> liked,
    List<String> suppressedItems,
    Map<String, Long> suppressedArtists,
    String sessionId,
    Map<String, Double> sessionGenres,
    String lastItemId,
    long updatedTs) {}
