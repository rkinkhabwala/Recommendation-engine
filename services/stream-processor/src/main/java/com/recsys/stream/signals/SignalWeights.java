package com.recsys.stream.signals;

import java.util.Map;

/**
 * Per-domain signal weights (docs/architecture.md §5). Config, not code: tune without redeploying
 * logic. Weights move the user's taste vectors and affinities; a threshold of 0 disables a rule.
 *
 * @param eventWeights flat weights for event types that need no context (LIKE, SAVE, CLICK, ...)
 * @param completeThreshold completion ratio counted as "complete" (songs, videos)
 * @param completeWeight weight of a complete listen/watch
 * @param listenedSeconds meaningful (counted) listen/watch threshold in seconds
 * @param listenedRatio alternative meaningful threshold as a completion ratio (videos: 25%)
 * @param listenedWeight weight of a meaningful but incomplete listen/watch
 * @param abandonRatio PLAY_END below this completion ratio is an abandon (videos: 10%)
 * @param abandonWeight weight of an abandon
 * @param earlySkipSeconds skips before this many seconds are strong negatives
 * @param earlySkipWeight weight of an early skip
 * @param midSkipSeconds skips before this are mild negatives
 * @param midSkipWeight weight of a mid skip
 * @param lateSkipRatio skips after this completion ratio are mild positives
 * @param lateSkipWeight weight of a late skip
 * @param browsingSkipFactor multiplier for early skips while the user is rapidly browsing
 * @param seekForwardRatio forward seek over this fraction of duration counts as impatience
 * @param seekForwardWeight weight of a big forward seek
 * @param dwellSeconds DWELL at or above this is engagement (posts, book detail pages)
 * @param dwellWeight weight of an engaged dwell
 * @param shortDwellSeconds DWELL below this is a scroll-past
 * @param shortDwellWeight weight of a scroll-past
 * @param followArtistWeight creator-affinity boost for FOLLOW (does not move the vector)
 * @param ratingWeightPerStar RATE weight = (stars - 3) × this
 */
public record SignalWeights(
    Map<String, Double> eventWeights,
    double completeThreshold,
    double completeWeight,
    double listenedSeconds,
    double listenedRatio,
    double listenedWeight,
    double abandonRatio,
    double abandonWeight,
    double earlySkipSeconds,
    double earlySkipWeight,
    double midSkipSeconds,
    double midSkipWeight,
    double lateSkipRatio,
    double lateSkipWeight,
    double browsingSkipFactor,
    double seekForwardRatio,
    double seekForwardWeight,
    double dwellSeconds,
    double dwellWeight,
    double shortDwellSeconds,
    double shortDwellWeight,
    double followArtistWeight,
    double ratingWeightPerStar) {

  /** Defaults matching docs/architecture.md §5 (used by tests; prod reads application.yml). */
  public static SignalWeights defaults(String domain) {
    return switch (domain) {
      case "video" ->
          new SignalWeights(
              Map.of(
                  "SHARE",
                  1.5,
                  "LIKE",
                  1.2,
                  "SAVE",
                  1.2,
                  "COMMENT",
                  1.0,
                  "DISLIKE",
                  -2.0,
                  "NOT_INTERESTED",
                  -3.0),
              0.9,
              1.5,
              30,
              0.25,
              0.5,
              0.10,
              -0.5,
              10,
              -0.5,
              30,
              -0.2,
              0.5,
              0.2,
              0.5,
              0.3,
              -0.1,
              0,
              0,
              0,
              0,
              1.0,
              0.75);
      case "book" ->
          new SignalWeights(
              Map.of(
                  "SAVE",
                  1.2,
                  "LIKE",
                  1.0,
                  "SHARE",
                  1.2,
                  "CLICK",
                  0.2,
                  "NOT_INTERESTED",
                  -3.0,
                  "DISLIKE",
                  -1.5),
              0,
              0,
              0,
              0,
              0,
              0,
              0,
              0,
              0,
              0,
              0,
              0,
              0,
              1,
              0,
              0,
              20,
              0.3,
              0,
              0,
              1.0,
              1.0);
      case "post" ->
          new SignalWeights(
              Map.of(
                  "COMMENT",
                  1.5,
                  "SHARE",
                  1.5,
                  "SAVE",
                  1.2,
                  "LIKE",
                  0.8,
                  "CLICK",
                  0.2,
                  "DISLIKE",
                  -1.5,
                  "NOT_INTERESTED",
                  -3.0),
              0,
              0,
              0,
              0,
              0,
              0,
              0,
              0,
              0,
              0,
              0,
              0,
              0,
              1,
              0,
              0,
              5,
              0.3,
              1,
              -0.05,
              1.0,
              0.75);
      default ->
          new SignalWeights(
              Map.of(
                  "SAVE",
                  2.0,
                  "LIKE",
                  1.5,
                  "SHARE",
                  1.5,
                  "REPLAY",
                  1.2,
                  "COMMENT",
                  1.0,
                  "SEARCH_RESULT_CLICK",
                  0.3,
                  "DISLIKE",
                  -2.0,
                  "NOT_INTERESTED",
                  -3.0),
              0.9,
              1.0,
              30,
              0,
              0.4,
              0,
              0,
              10,
              -0.8,
              30,
              -0.3,
              0.5,
              0.2,
              0.5,
              0.3,
              -0.1,
              0,
              0,
              0,
              0,
              1.0,
              0.75);
    };
  }

  /** Song defaults (Phase 1 API, kept for tests). */
  public static SignalWeights songDefaults() {
    return defaults("song");
  }
}
