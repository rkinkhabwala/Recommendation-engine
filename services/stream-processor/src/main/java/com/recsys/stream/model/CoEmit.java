package com.recsys.stream.model;

/** One co-engagement observation: {@code NEXT} (ordered A→B in a session) or {@code CO}. */
public record CoEmit(
    String fromItem, String toItem, String kind, double weight, long ts, long receivedTs) {
  public static final String NEXT = "next";
  public static final String CO = "co";
}
