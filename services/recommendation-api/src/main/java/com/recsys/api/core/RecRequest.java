package com.recsys.api.core;

/** Validated request. {@code surface}: home, next_track, radio. */
public record RecRequest(
    String userId,
    String domain,
    String surface,
    int limit,
    String sessionId,
    String seedItemId,
    String country,
    String device,
    boolean explicitAllowed) {

  public boolean nextTrack() {
    return "next_track".equals(surface);
  }
}
