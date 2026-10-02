package com.recsys.common;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Dense vector math and compact binary codecs. */
public final class Vectors {
  private Vectors() {}

  public static float dot(float[] a, float[] b) {
    float s = 0f;
    for (int i = 0; i < a.length; i++) {
      s += a[i] * b[i];
    }
    return s;
  }

  public static float norm(float[] a) {
    return (float) Math.sqrt(dot(a, a));
  }

  /** Returns a unit-length copy, or null when the vector is (near) zero. */
  public static float[] normalized(float[] a) {
    if (a == null) {
      return null;
    }
    float n = norm(a);
    if (n < 1e-9f) {
      return null;
    }
    float[] out = new float[a.length];
    for (int i = 0; i < a.length; i++) {
      out[i] = a[i] / n;
    }
    return out;
  }

  public static float cosine(float[] a, float[] b) {
    float na = norm(a);
    float nb = norm(b);
    return (na < 1e-9f || nb < 1e-9f) ? 0f : dot(a, b) / (na * nb);
  }

  /** a + w*b, written into a new array. Either input may be null. */
  public static float[] blend(float[] a, float wa, float[] b, float wb) {
    if (a == null && b == null) {
      return null;
    }
    int n = a != null ? a.length : b.length;
    float[] out = new float[n];
    for (int i = 0; i < n; i++) {
      out[i] = (a != null ? wa * a[i] : 0f) + (b != null ? wb * b[i] : 0f);
    }
    return out;
  }

  /** IEEE half precision, little endian: 2 bytes per dimension (used for Redis payloads). */
  public static byte[] toFloat16(float[] v) {
    ByteBuffer buf = ByteBuffer.allocate(v.length * 2).order(ByteOrder.LITTLE_ENDIAN);
    for (float f : v) {
      buf.putShort(Float.floatToFloat16(f));
    }
    return buf.array();
  }

  public static float[] fromFloat16(byte[] bytes) {
    if (bytes == null) {
      return null;
    }
    ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    float[] out = new float[bytes.length / 2];
    for (int i = 0; i < out.length; i++) {
      out[i] = Float.float16ToFloat(buf.getShort());
    }
    return out;
  }

  /** Full precision, little endian: 4 bytes per dimension (used for accumulators). */
  public static byte[] toFloat32(float[] v) {
    ByteBuffer buf = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
    for (float f : v) {
      buf.putFloat(f);
    }
    return buf.array();
  }

  public static float[] fromFloat32(byte[] bytes) {
    if (bytes == null) {
      return null;
    }
    ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    float[] out = new float[bytes.length / 4];
    for (int i = 0; i < out.length; i++) {
      out[i] = buf.getFloat();
    }
    return out;
  }

  public static float[] fromList(List<Float> list) {
    float[] out = new float[list.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = list.get(i);
    }
    return out;
  }

  public static List<Float> toList(float[] v) {
    List<Float> out = new ArrayList<>(v.length);
    for (float f : v) {
      out.add(f);
    }
    return out;
  }
}
