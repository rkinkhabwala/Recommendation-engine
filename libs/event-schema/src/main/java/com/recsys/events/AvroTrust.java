package com.recsys.events;

import org.apache.avro.util.ClassSecurityValidator;

/**
 * Avro 1.12 only instantiates trusted classes when deserializing SpecificRecords. Call {@link
 * #install()} once at startup in any process that deserializes our contract records.
 */
public final class AvroTrust {
  private static final String PACKAGE = "com.recsys.events.v1";
  private static volatile boolean installed;

  private AvroTrust() {}

  public static synchronized void install() {
    if (installed) {
      return;
    }
    ClassSecurityValidator.setGlobal(
        ClassSecurityValidator.composite(
            ClassSecurityValidator.getGlobal(), c -> PACKAGE.equals(c.getPackageName())));
    installed = true;
  }
}
