package com.recsys.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class VectorsTest {

  @Test
  void float16RoundTripKeepsDirection() {
    float[] v = Vectors.normalized(new float[] {0.12f, -0.5f, 0.33f, 0.9f});
    float[] back = Vectors.fromFloat16(Vectors.toFloat16(v));
    assertThat(Vectors.cosine(v, back)).isCloseTo(1f, within(1e-3f));
  }

  @Test
  void float32RoundTripIsExact() {
    float[] v = {1.5f, -2.25f, 3.125f};
    assertThat(Vectors.fromFloat32(Vectors.toFloat32(v))).containsExactly(v);
  }

  @Test
  void zeroVectorNormalizesToNull() {
    assertThat(Vectors.normalized(new float[3])).isNull();
  }

  @Test
  void uuidV7IsVersion7AndTimeOrdered() {
    var a = Ids.uuidV7(1_000L);
    var b = Ids.uuidV7(2_000L);
    assertThat(a.version()).isEqualTo(7);
    assertThat(a.toString()).isLessThan(b.toString());
    assertThat(Ids.isUuid(a.toString())).isTrue();
  }

  @Test
  void nameBasedIdsAreDeterministic() {
    assertThat(Ids.nameBased("item", "s_1")).isEqualTo(Ids.nameBased("item", "s_1"));
    assertThat(Ids.nameBased("item", "s_1")).isNotEqualTo(Ids.nameBased("item", "s_2"));
  }

  @Test
  void indexVersionEncodesDims() {
    var v = IndexVersion.parse("items_te3s_512_v1");
    assertThat(v.dims()).isEqualTo(512);
    assertThatThrownBy(() -> v.requireDims(1536)).isInstanceOf(IllegalStateException.class);
  }
}
