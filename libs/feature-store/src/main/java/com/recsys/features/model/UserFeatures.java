package com.recsys.features.model;

/** Everything the serving path reads about a user in one round trip. Any part may be null. */
public record UserFeatures(UserShortTerm shortTerm, UserVector longTerm, UserVector seed) {
  public static final UserFeatures EMPTY = new UserFeatures(null, null, null);

  public boolean isEmpty() {
    return shortTerm == null && longTerm == null && seed == null;
  }
}
