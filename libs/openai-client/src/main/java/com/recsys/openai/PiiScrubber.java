package com.recsys.openai;

import java.util.regex.Pattern;

/**
 * Best-effort removal of direct identifiers from user free text before it leaves our systems.
 * Defence in depth: the request also carries no user id. TODO(phase-4): NER-based name detection.
 */
public final class PiiScrubber {
  private static final Pattern EMAIL =
      Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
  private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");
  private static final Pattern PHONE = Pattern.compile("\\+?\\d[\\d\\s().-]{7,}\\d");
  private static final Pattern HANDLE = Pattern.compile("(?<![\\w@])@\\w{2,}");
  private static final Pattern LONG_NUMBER = Pattern.compile("\\b\\d{6,}\\b");

  private PiiScrubber() {}

  public static String scrub(String text) {
    if (text == null) {
      return null;
    }
    String s = EMAIL.matcher(text).replaceAll("[email]");
    s = URL.matcher(s).replaceAll("[url]");
    s = PHONE.matcher(s).replaceAll("[phone]");
    s = HANDLE.matcher(s).replaceAll("[handle]");
    s = LONG_NUMBER.matcher(s).replaceAll("[number]");
    return s.strip();
  }
}
