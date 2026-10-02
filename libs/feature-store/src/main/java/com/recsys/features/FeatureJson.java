package com.recsys.features;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.UncheckedIOException;

/** JSON codec for feature payloads (internal format, owned by this module). */
public final class FeatureJson {
  public static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  private FeatureJson() {}

  public static byte[] write(Object value) {
    try {
      return MAPPER.writeValueAsBytes(value);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static <T> T read(byte[] bytes, Class<T> type) {
    if (bytes == null) {
      return null;
    }
    try {
      return MAPPER.readValue(bytes, type);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
