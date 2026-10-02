package com.recsys.common;

/**
 * Exponentially time-decayed sum, expressed at reference time {@code refTs} (the latest event time
 * seen). Adding is order-independent: a late event is discounted by its age relative to refTs, so
 * the result equals Σ w_i·2^(-(refTs - t_i)/h) regardless of arrival order. Mutable for cheap
 * updates inside state stores.
 */
public final class DecayedScalar {
  public double value;
  public long refTs;

  public DecayedScalar() {}

  public DecayedScalar(double value, long refTs) {
    this.value = value;
    this.refTs = refTs;
  }

  public void add(long ts, double weight, long halfLifeMs) {
    if (refTs == 0) {
      refTs = ts;
    }
    if (ts > refTs) {
      value *= Decay.factor(ts - refTs, halfLifeMs);
      refTs = ts;
      value += weight;
    } else {
      value += weight * Decay.factor(refTs - ts, halfLifeMs);
    }
  }

  /** Value as seen at {@code now} (decayed further, never "un-decayed"). */
  public double valueAt(long now, long halfLifeMs) {
    return value * Decay.factor(now - refTs, halfLifeMs);
  }
}
