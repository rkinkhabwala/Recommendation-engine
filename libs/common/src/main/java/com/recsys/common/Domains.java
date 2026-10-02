package com.recsys.common;

import java.util.List;

/** Content domains (lowercase, as used in APIs and Redis keys). */
public final class Domains {
  public static final String SONG = "song";
  public static final String BOOK = "book";
  public static final String VIDEO = "video";
  public static final String POST = "post";
  public static final List<String> ALL = List.of(SONG, BOOK, VIDEO, POST);

  private Domains() {}
}
