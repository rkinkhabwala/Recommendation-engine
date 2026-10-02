package com.recsys.features;

import static org.assertj.core.api.Assertions.assertThat;

import com.recsys.common.Vectors;
import com.recsys.features.model.UserVector;
import org.junit.jupiter.api.Test;

class FeatureEnvelopeTest {

  @Test
  void roundTripsPayloadIncludingBinaryVector() {
    float[] v = Vectors.normalized(new float[] {1, 2, 3});
    var uv = new UserVector("u1", "items_mock_3_v1", Vectors.toFloat16(v), "lt", 42);
    byte[] bytes = FeatureEnvelope.encode(7, 3600, 41, uv);

    FeatureEnvelope env = FeatureEnvelope.decode(bytes);
    assertThat(env.seq()).isEqualTo(7);
    assertThat(env.ttlSeconds()).isEqualTo(3600);
    UserVector back = FeatureJson.read(env.dataBytes(), UserVector.class);
    assertThat(back.indexVersion()).isEqualTo("items_mock_3_v1");
    assertThat(Vectors.cosine(v, Vectors.fromFloat16(back.vector()))).isGreaterThan(0.999f);
  }

  @Test
  void userKeysShareHashTag() {
    assertThat(RedisKeys.userShortTerm("u_9", "song")).isEqualTo("u:{u_9}:st:song");
    assertThat(RedisKeys.userIdOf("u:{u_9}:lt:book")).isEqualTo("u_9");
    assertThat(RedisKeys.allUserKeys("u_9")).hasSize(13).contains("u:{u_9}:x");
    assertThat(RedisKeys.explanation("song", "LISTENED_TOGETHER", "s_1", "s_2"))
        .startsWith("expl:")
        .isEqualTo(RedisKeys.explanation("song", "LISTENED_TOGETHER", "s_1", "s_2"));
    assertThat(RedisKeys.userIdOf("i:{s_1}:stat")).isNull();
  }
}
