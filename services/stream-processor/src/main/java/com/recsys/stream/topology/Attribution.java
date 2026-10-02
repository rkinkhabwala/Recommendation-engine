package com.recsys.stream.topology;

import com.recsys.events.v1.Domain;
import com.recsys.events.v1.Outcome;
import com.recsys.events.v1.RecommendationAttributed;
import com.recsys.stream.model.EventView;
import com.recsys.stream.model.ServedRef;
import com.recsys.stream.signals.SignalWeigher;
import com.recsys.stream.signals.SignalWeighers;
import java.time.Instant;
import java.util.Locale;

/** Maps (engagement event, served item) pairs to attributed outcomes; null = not an outcome. */
final class Attribution {
  private final SignalWeighers weighers;

  Attribution(SignalWeighers weighers) {
    this.weighers = weighers;
  }

  RecommendationAttributed join(EventView e, ServedRef s) {
    SignalWeigher weigher = weighers.get(e.domain());
    Outcome outcome =
        switch (e.eventType()) {
          case "IMPRESSION" -> Outcome.IMPRESSED;
          case "PLAY_START", "CLICK" -> Outcome.PLAYED; // "played" = started consuming (open/play)
          case "DWELL" -> weigher.isEngagedDwell(e) ? Outcome.PLAYED : null;
          case "RATE" ->
              e.value() == null
                  ? null
                  : e.value() >= 4 ? Outcome.LIKED : e.value() <= 2 ? Outcome.DISLIKED : null;
          case "PLAY_END" ->
              weigher.classify(e, e.durationMs()) == SignalWeigher.Kind.COMPLETE
                  ? Outcome.COMPLETED
                  : null;
          case "SKIP" -> weigher.isEarlySkip(e) ? Outcome.SKIPPED_EARLY : null;
          case "LIKE" -> Outcome.LIKED;
          case "SAVE" -> Outcome.SAVED;
          case "DISLIKE", "NOT_INTERESTED" -> Outcome.DISLIKED;
          default -> null;
        };
    if (outcome == null) {
      return null;
    }
    return RecommendationAttributed.newBuilder()
        .setRecommendationId(s.recommendationId())
        .setItemId(s.itemId())
        .setPosition(s.position())
        .setUserId(s.userId())
        .setDomain(Domain.valueOf(e.domain().toUpperCase(Locale.ROOT)))
        .setVariantId(s.variantId())
        .setExplore(s.explore())
        .setOutcome(outcome)
        .setServedTs(Instant.ofEpochMilli(s.servedTs()))
        .setOutcomeTs(Instant.ofEpochMilli(e.eventTs()))
        .build();
  }
}
