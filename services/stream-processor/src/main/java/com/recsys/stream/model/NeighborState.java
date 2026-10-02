package com.recsys.stream.model;

import com.recsys.common.DecayedScalar;
import java.util.HashMap;
import java.util.Map;

/** Decayed co-engagement counts from one item to others. */
public class NeighborState {
  public long seqNext;
  public long seqCo;
  public long lastEmitNextWallMs;
  public long lastEmitCoWallMs;
  public long lastReceivedTs;
  public Map<String, DecayedScalar> next = new HashMap<>();
  public Map<String, DecayedScalar> co = new HashMap<>();
}
