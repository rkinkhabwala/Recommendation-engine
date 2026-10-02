package com.recsys.stream.signals;

import java.util.Map;

/**
 * Per-domain signal weights (docs/architecture.md §5). Config, not code: tune without redeploying
 * logic. Weights apply to the user's taste vector and affinities.
 *
 * @param eventWeights flat weights for event types that need no context (LIKE, SAVE, ...)
 * @param completeThreshold completion ratio counted as "complete"
 * @param completeWeight weight for a complete listen
 * @param listenedSeconds threshold for a meaningful (counted) listen
 * @param listenedWeight weight for a meaningful but incomplete listen
 * @param earlySkipSeconds skips before this many seconds are strong negatives
 * @param earlySkipWeight weight of an early skip
 * @param midSkipSeconds skips before this are mild negatives
 * @param midSkipWeight weight of a mid skip
 * @param lateSkipRatio skips after this completion ratio are mild positives
 * @param lateSkipWeight weight of a late skip
 * @param browsingSkipFactor multiplier for early skips while the user is rapidly browsing
 * @param seekForwardRatio forward seek over this fraction of duration counts as impatience
 * @param seekForwardWeight weight of a big forward seek
 * @param followArtistWeight artist-affinity boost for FOLLOW (does not move the vector)
 * @param ratingWeightPerStar RATE weight = (stars - 3) × this
 */
public record SignalWeights(
    Map<String, Double> eventWeights,
    double completeThreshold,
    double completeWeight,
    double listenedSeconds,
    double listenedWeight,
    double earlySkipSeconds,
    double earlySkipWeight,
    double midSkipSeconds,
    double midSkipWeight,
    double lateSkipRatio,
    double lateSkipWeight,
    double browsingSkipFactor,
    double seekForwardRatio,
    double seekForwardWeight,
    double followArtistWeight,
    double ratingWeightPerStar) {

  /** Song defaults matching docs/architecture.md §5 (used by tests; prod reads YAML). */
  public static SignalWeights songDefaults() {
    return new SignalWeights(
        Map.of(
            "SAVE", 2.0,
            "LIKE", 1.5,
            "SHARE", 1.5,
            "REPLAY", 1.2,
            "COMMENT", 1.0,
            "SEARCH_RESULT_CLICK", 0.3,
            "DISLIKE", -2.0,
            "NOT_INTERESTED", -3.0),
        0.9,
        1.0,
        30,
        0.4,
        10,
        -0.8,
        30,
        -0.3,
        0.5,
        0.2,
        0.5,
        0.3,
        -0.1,
        1.0,
        0.75);
  }
}
