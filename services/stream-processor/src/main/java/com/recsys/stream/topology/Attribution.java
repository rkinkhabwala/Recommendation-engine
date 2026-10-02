package com.recsys.stream.topology;

import com.recsys.events.v1.Outcome;
import com.recsys.events.v1.RecommendationAttributed;
import com.recsys.stream.model.EventView;
import com.recsys.stream.model.ServedRef;
import com.recsys.stream.signals.SignalWeigher;
import java.time.Instant;

/** Maps (engagement event, served item) pairs to attributed outcomes; null = not an outcome. */
final class Attribution {
  private final SignalWeigher weigher;

  Attribution(SignalWeigher weigher) {
    this.weigher = weigher;
  }

  RecommendationAttributed join(EventView e, ServedRef s) {
    Outcome outcome =
        switch (e.eventType()) {
          case "PLAY_START", "CLICK" -> Outcome.PLAYED;
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
        .setVariantId(s.variantId())
        .setExplore(s.explore())
        .setOutcome(outcome)
        .setServedTs(Instant.ofEpochMilli(s.servedTs()))
        .setOutcomeTs(Instant.ofEpochMilli(e.eventTs()))
        .build();
  }
}
