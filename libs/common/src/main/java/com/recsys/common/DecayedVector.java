package com.recsys.common;

/**
 * Time-decayed, engagement-weighted vector sum (the user's taste vector). Positive and negative
 * evidence accumulate separately so the negative contribution can be capped relative to positive
 * mass: a burst of skips should push the vector away from what was skipped, not flip the user's
 * whole taste. Same order-independence property as {@link DecayedScalar}.
 */
public final class DecayedVector {
  public float[] pos;
  public float[] neg;
  public double posMass;
  public double negMass;
  public long refTs;

  public DecayedVector() {}

  public void add(long ts, double weight, float[] itemVector, long halfLifeMs) {
    if (weight == 0 || itemVector == null) {
      return;
    }
    if (pos == null) {
      pos = new float[itemVector.length];
      neg = new float[itemVector.length];
      refTs = ts;
    }
    if (itemVector.length != pos.length) {
      throw new IllegalArgumentException(
          "Vector dims changed: " + pos.length + " -> " + itemVector.length);
    }
    double discount;
    if (ts > refTs) {
      float f = (float) Decay.factor(ts - refTs, halfLifeMs);
      scale(pos, f);
      scale(neg, f);
      posMass *= f;
      negMass *= f;
      refTs = ts;
      discount = 1.0;
    } else {
      discount = Decay.factor(refTs - ts, halfLifeMs);
    }
    float w = (float) (Math.abs(weight) * discount);
    float[] target = weight > 0 ? pos : neg;
    for (int i = 0; i < target.length; i++) {
      target[i] += w * itemVector[i];
    }
    if (weight > 0) {
      posMass += w;
    } else {
      negMass += w;
    }
  }

  /**
   * Unit-length effective vector: pos - neg·min(1, cap·posMass/negMass). Null when there is no
   * positive evidence (a vector built only from dislikes is not a taste).
   */
  public float[] effective(double negativeCapRatio) {
    if (pos == null || posMass <= 1e-9) {
      return null;
    }
    double negScale = negMass <= 1e-9 ? 0 : Math.min(1.0, negativeCapRatio * posMass / negMass);
    float[] out = new float[pos.length];
    for (int i = 0; i < out.length; i++) {
      out[i] = (float) (pos[i] / posMass - negScale * neg[i] / posMass);
    }
    return Vectors.normalized(out);
  }

  public boolean isEmpty() {
    return pos == null;
  }

  private static void scale(float[] v, float f) {
    for (int i = 0; i < v.length; i++) {
      v[i] *= f;
    }
  }
}
