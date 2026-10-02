package com.recsys.stream.signals;

import com.recsys.stream.model.EventView;

/** Maps an event to a signed engagement weight and to an item-statistics class. */
public final class SignalWeigher {

  /** Item-statistics classification of an event (independent of user state). */
  public enum Kind {
    IMPRESSION,
    START,
    COMPLETE,
    EARLY_SKIP,
    SKIP,
    END,
    OTHER
  }

  private final SignalWeights w;

  public SignalWeigher(SignalWeights weights) {
    this.w = weights;
  }

  public SignalWeights weights() {
    return w;
  }

  /**
   * @param durationMs item duration (from the event, else catalog), may be null
   * @param browsing user is rapidly skipping with autoplay off (searching, not rejecting)
   */
  public double weight(EventView e, Long durationMs, boolean browsing) {
    String type = e.eventType();
    Double listened = e.listenedSeconds();
    Double completion =
        listened != null && durationMs != null && durationMs > 0
            ? listened * 1000.0 / durationMs
            : null;
    return switch (type) {
      case "PLAY_END" -> {
        if (completion != null && completion >= w.completeThreshold()) {
          yield w.completeWeight();
        }
        yield listened != null && listened >= w.listenedSeconds() ? w.listenedWeight() : 0;
      }
      case "SKIP" -> {
        if (listened == null) {
          yield w.midSkipWeight();
        }
        if (listened < w.earlySkipSeconds()) {
          yield browsing ? w.earlySkipWeight() * w.browsingSkipFactor() : w.earlySkipWeight();
        }
        if (listened < w.midSkipSeconds()) {
          yield w.midSkipWeight();
        }
        yield completion != null && completion >= w.lateSkipRatio() ? w.lateSkipWeight() : 0;
      }
      case "SEEK" -> {
        if (e.seekFromMs() != null
            && e.seekToMs() != null
            && durationMs != null
            && durationMs > 0) {
          double jump = (e.seekToMs() - e.seekFromMs()) / (double) durationMs;
          yield jump > w.seekForwardRatio() ? w.seekForwardWeight() : 0;
        }
        yield 0;
      }
      case "RATE" -> e.value() == null ? 0 : (e.value() - 3) * w.ratingWeightPerStar();
      default -> w.eventWeights().getOrDefault(type, 0.0);
    };
  }

  public boolean isEarlySkip(EventView e) {
    Double listened = e.listenedSeconds();
    return "SKIP".equals(e.eventType()) && listened != null && listened < w.earlySkipSeconds();
  }

  public Kind classify(EventView e, Long durationMs) {
    return switch (e.eventType()) {
      case "IMPRESSION" -> Kind.IMPRESSION;
      case "PLAY_START" -> Kind.START;
      case "SKIP" -> isEarlySkip(e) ? Kind.EARLY_SKIP : Kind.SKIP;
      case "PLAY_END" -> {
        Double listened = e.listenedSeconds();
        boolean complete =
            listened != null
                && durationMs != null
                && durationMs > 0
                && listened * 1000.0 / durationMs >= w.completeThreshold();
        yield complete ? Kind.COMPLETE : Kind.END;
      }
      default -> Kind.OTHER;
    };
  }
}
