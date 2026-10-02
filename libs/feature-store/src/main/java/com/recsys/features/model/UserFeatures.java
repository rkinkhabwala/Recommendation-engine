package com.recsys.features.model;

/**
 * Everything the serving path reads about a user for one domain in one round trip. Any part may be
 * null. {@code crossDomain} is built from engagement in every domain (shared embedding space).
 */
public record UserFeatures(
    UserShortTerm shortTerm, UserVector longTerm, UserVector seed, UserVector crossDomain) {
  public static final UserFeatures EMPTY = new UserFeatures(null, null, null, null);

  public boolean isEmpty() {
    return shortTerm == null && longTerm == null && seed == null && crossDomain == null;
  }
}
