package com.recsys.api.candidates;

import com.recsys.api.core.ReasonCode;
import com.recsys.features.model.ItemMeta;
import com.recsys.features.model.ItemStats;
import com.recsys.vector.ItemPayload;
import java.util.LinkedHashMap;
import java.util.Map;

/** A merged candidate moving through hydration, ranking and re-ranking. Mutable by design. */
public final class ScoredCandidate {
  public final String itemId;
  public final Map<String, Double> sourceScores = new LinkedHashMap<>();
  public ReasonCode reason;
  public String seedItemId;
  private double bestReasonScore = -1;
  public Float semantic;
  public ItemPayload payload;
  public ItemMeta meta;
  public ItemStats stats;
  public double score;

  /** Ranking features in {@link com.recsys.api.ranking.FeatureExtractor#FEATURES} order. */
  public double[] featureVector;

  public Map<String, Float> features = Map.of();
  public String explanation;
  public boolean explore;
  public Double propensity;

  public ScoredCandidate(String itemId) {
    this.itemId = itemId;
  }

  /** Reason and seed proposed by each source (for contribution-based reason attribution). */
  public final Map<String, Candidate> bySource = new LinkedHashMap<>();

  public void absorb(Candidate c) {
    sourceScores.merge(c.source(), c.score(), Math::max);
    bySource.merge(c.source(), c, (a, b) -> b.score() > a.score() ? b : a);
    if (c.annScore() != null && (semantic == null || c.annScore() > semantic)) {
      semantic = c.annScore();
    }
    if (c.payload() != null && payload == null) {
      payload = c.payload();
    }
    if (c.score() > bestReasonScore) {
      bestReasonScore = c.score();
      reason = c.reason();
      seedItemId = c.seedItemId();
    }
  }

  public double source(String name) {
    return sourceScores.getOrDefault(name, 0.0);
  }

  /** Artist from catalog metadata, else from the vector payload. */
  public String artistId() {
    if (meta != null) {
      return meta.artistId();
    }
    return payload == null ? null : payload.artistId();
  }

  public String primaryGenre() {
    if (meta != null && !meta.genres().isEmpty()) {
      return meta.genres().get(0);
    }
    return payload == null || payload.genres().isEmpty() ? null : payload.genres().get(0);
  }

  @Override
  public String toString() {
    return itemId + "(" + String.format("%.3f", score) + ")";
  }
}
