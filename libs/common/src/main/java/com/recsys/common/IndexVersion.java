package com.recsys.common;

import java.util.regex.Pattern;

/**
 * Vector index version, e.g. {@code items_te3s_512_v1} = model slug, dimensions, revision. The
 * dimension is part of the name so vectors of different spaces can never be mixed silently.
 */
public record IndexVersion(String name, String modelSlug, int dims, int revision) {
  private static final Pattern FORMAT = Pattern.compile("^items_([a-z0-9]+)_(\\d+)_v(\\d+)$");

  public static IndexVersion parse(String name) {
    var m = FORMAT.matcher(name);
    if (!m.matches()) {
      throw new IllegalArgumentException(
          "Index version must look like items_<model>_<dims>_v<n>, got: " + name);
    }
    return new IndexVersion(
        name, m.group(1), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
  }

  /** Fails fast when config says one dimension but the index name encodes another. */
  public IndexVersion requireDims(int expected) {
    if (dims != expected) {
      throw new IllegalStateException(
          "Index " + name + " encodes " + dims + " dims but embedding config says " + expected);
    }
    return this;
  }
}
