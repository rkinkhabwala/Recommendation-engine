package com.recsys.stream.model;

import com.recsys.common.DecayedScalar;
import com.recsys.common.DecayedVector;
import com.recsys.features.model.RecentInteraction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A user's real-time state within one domain. Mutable for speed. */
public class DomainState {
  public long seq;
  public long ltSeq;
  public long lastLtEmitWallMs;
  public long lastEventTs;
  public long lastReceivedTs;

  public DecayedVector shortVec = new DecayedVector();
  public DecayedVector longVec = new DecayedVector();
  public Map<String, DecayedScalar> artistAff = new HashMap<>();
  public Map<String, DecayedScalar> genreAff = new HashMap<>();
  public Map<String, DecayedScalar> moodAff = new HashMap<>();

  public List<RecentInteraction> recent = new ArrayList<>();
  public Map<String, Long> consumed = new HashMap<>();
  public List<String> liked = new ArrayList<>();
  public List<String> suppressedItems = new ArrayList<>();
  public Map<String, Long> suppressedArtists = new HashMap<>();

  public String sessionId;
  public long sessionStart;
  public Map<String, Double> sessionGenres = new HashMap<>();
  public List<String> sessionPositives = new ArrayList<>();
  public String lastPositiveItem;
  public long lastPositiveTs;
  public int sessionCoEmits;
  public List<Long> recentEarlySkips = new ArrayList<>();
}
