package com.recsys.stream.model;

import com.recsys.common.DecayedScalar;

/** Per-item decayed counters (order-independent, so late events need no special handling). */
public class ItemStatsState {
  public long seq;
  public long lastEmitWallMs;
  public long lastReceivedTs;
  public DecayedScalar impressions = new DecayedScalar();
  public DecayedScalar starts = new DecayedScalar();
  public DecayedScalar ends = new DecayedScalar();
  public DecayedScalar completes = new DecayedScalar();
  public DecayedScalar earlySkips = new DecayedScalar();
  public DecayedScalar plays1h = new DecayedScalar();
}
