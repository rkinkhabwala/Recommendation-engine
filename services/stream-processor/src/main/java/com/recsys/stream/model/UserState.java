package com.recsys.stream.model;

import com.recsys.common.DecayedVector;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-user state in the {@code user-state} store (RocksDB, changelogged): one {@link DomainState}
 * per domain plus a cross-domain taste vector built from engagement in every domain.
 */
public class UserState {
  public String indexVersion;
  public long xSeq;
  public long lastXEmitWallMs;
  public long lastEventTs;
  public long lastReceivedTs;
  public DecayedVector crossVec = new DecayedVector();
  public Map<String, DomainState> domains = new HashMap<>();
}
