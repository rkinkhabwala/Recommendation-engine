package com.recsys.openai;

import com.recsys.common.Vectors;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic offline embedder using signed feature hashing of tokens. Texts that share words
 * (artist, genres, moods) get similar vectors, so local recommendations are meaningfully "semantic"
 * without an API key. Each token is hashed into several dimensions to reduce collisions.
 */
public final class MockEmbeddingClient implements EmbeddingClient {
  private static final int PROJECTIONS = 4;

  /** Embedder revision; part of {@link #model()} so a change re-embeds into a new index. */
  static final String REVISION = "mock2";

  private static final java.util.Set<String> STOPWORDS =
      java.util.Set.of(
          "the", "by", "and", "of", "an", "to", "in", "on", "for", "with", "about", "that", "it",
          "is", "at", "from", "this", "its", "plus", "some", "can", "we", "my", "just", "genres",
          "mood", "themes", "topics", "tone", "author", "channel", "poster");

  private final String model;
  private final int dims;
  private final CostMeter cost;

  public MockEmbeddingClient(String model, int dims, CostMeter cost) {
    this.model = model;
    this.dims = dims;
    this.cost = cost;
  }

  @Override
  public EmbeddingResponse embed(List<String> inputs, String job, JobPriority priority) {
    List<float[]> out = new ArrayList<>(inputs.size());
    long tokens = 0;
    for (String text : inputs) {
      float[] v = new float[dims];
      String[] words = text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+");
      for (String w : words) {
        if (w.length() < 2 || STOPWORDS.contains(w)) {
          continue;
        }
        tokens++;
        for (int p = 0; p < PROJECTIONS; p++) {
          int h = murmur(w + "#" + p);
          int idx = Math.floorMod(h, dims);
          v[idx] += (h & 0x40000000) != 0 ? 1f : -1f;
        }
      }
      float[] n = Vectors.normalized(v);
      out.add(n != null ? n : unit(dims));
    }
    if (cost != null) {
      cost.record(model, job, tokens);
    }
    return new EmbeddingResponse(out, tokens);
  }

  private static float[] unit(int dims) {
    float[] v = new float[dims];
    v[0] = 1f;
    return v;
  }

  @Override
  public String model() {
    return model;
  }

  @Override
  public int dimensions() {
    return dims;
  }

  /** 32-bit murmur3 finalizer over UTF-8 bytes; stable across JVMs. */
  static int murmur(String s) {
    byte[] data = s.getBytes(StandardCharsets.UTF_8);
    int h = 0x9747b28c;
    for (byte b : data) {
      int k = b & 0xFF;
      k *= 0xcc9e2d51;
      k = Integer.rotateLeft(k, 15);
      k *= 0x1b873593;
      h ^= k;
      h = Integer.rotateLeft(h, 13);
      h = h * 5 + 0xe6546b64;
    }
    h ^= data.length;
    h ^= h >>> 16;
    h *= 0x85ebca6b;
    h ^= h >>> 13;
    h *= 0xc2b2ae35;
    h ^= h >>> 16;
    return h;
  }
}
