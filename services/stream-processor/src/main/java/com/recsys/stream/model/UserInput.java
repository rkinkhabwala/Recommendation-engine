package com.recsys.stream.model;

/** Input of the user processor: either an enriched event or a deletion request. */
public record UserInput(EnrichedEvent event, String deletedUserId) {
  public static UserInput event(EnrichedEvent e) {
    return new UserInput(e, null);
  }

  public static UserInput deletion(String userId) {
    return new UserInput(null, userId);
  }

  public boolean isDeletion() {
    return deletedUserId != null;
  }
}
