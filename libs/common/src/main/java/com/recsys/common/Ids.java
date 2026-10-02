package com.recsys.common;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.regex.Pattern;

/** Identifier helpers. UUIDv7 is time-ordered, so ids sort roughly by creation time. */
public final class Ids {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Pattern UUID_PATTERN =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

  private Ids() {}

  /** RFC 9562 UUID version 7: 48-bit unix millis, version, 74 random bits. */
  public static UUID uuidV7(long epochMillis) {
    byte[] rand = new byte[10];
    RANDOM.nextBytes(rand);
    long msb = (epochMillis & 0xFFFF_FFFF_FFFFL) << 16;
    msb |= 0x7000L; // version 7
    msb |= ((rand[0] & 0x0FL) << 8) | (rand[1] & 0xFFL);
    long lsb = 0x8000_0000_0000_0000L; // IETF variant
    lsb |= (rand[2] & 0x3FL) << 56;
    for (int i = 3; i < 10; i++) {
      lsb |= (rand[i] & 0xFFL) << (8 * (9 - i));
    }
    return new UUID(msb, lsb);
  }

  public static String newId() {
    return uuidV7(System.currentTimeMillis()).toString();
  }

  public static boolean isUuid(String value) {
    return value != null && UUID_PATTERN.matcher(value).matches();
  }

  /** Deterministic name-based UUID (v5-style, SHA-1), e.g. Qdrant point id from item id. */
  public static UUID nameBased(String namespace, String name) {
    try {
      var sha1 = java.security.MessageDigest.getInstance("SHA-1");
      byte[] hash =
          sha1.digest((namespace + ":" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      hash[6] = (byte) ((hash[6] & 0x0F) | 0x50);
      hash[8] = (byte) ((hash[8] & 0x3F) | 0x80);
      long msb = 0;
      long lsb = 0;
      for (int i = 0; i < 8; i++) {
        msb = (msb << 8) | (hash[i] & 0xFF);
      }
      for (int i = 8; i < 16; i++) {
        lsb = (lsb << 8) | (hash[i] & 0xFF);
      }
      return new UUID(msb, lsb);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
