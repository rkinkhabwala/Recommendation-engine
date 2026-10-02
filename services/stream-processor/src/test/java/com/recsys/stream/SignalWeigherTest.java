package com.recsys.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.recsys.stream.model.EventView;
import com.recsys.stream.signals.SignalWeigher;
import com.recsys.stream.signals.SignalWeights;
import org.junit.jupiter.api.Test;

class SignalWeigherTest {
  final SignalWeigher w = new SignalWeigher(SignalWeights.songDefaults());

  static EventView ev(String type, Double value) {
    return new EventView(
        "e", "u", "s", "song", type, value, 0, 0, "sess", null, true, null, 200_000L, null, null,
        null, null, null);
  }

  @Test
  void songWeightsMatchTheDesign() {
    assertThat(w.weight(ev("PLAY_END", 190.0), 200_000L, false)).isEqualTo(1.0); // 95% complete
    assertThat(w.weight(ev("PLAY_END", 45.0), 200_000L, false)).isEqualTo(0.4); // counted stream
    assertThat(w.weight(ev("PLAY_END", 12.0), 200_000L, false)).isZero();
    assertThat(w.weight(ev("SKIP", 5.0), 200_000L, false)).isEqualTo(-0.8); // early skip
    assertThat(w.weight(ev("SKIP", 5.0), 200_000L, true)).isEqualTo(-0.4); // browsing: halved
    assertThat(w.weight(ev("SKIP", 20.0), 200_000L, false)).isEqualTo(-0.3);
    assertThat(w.weight(ev("SKIP", 60.0), 200_000L, false)).isZero(); // ambiguous
    assertThat(w.weight(ev("SKIP", 150.0), 200_000L, false)).isEqualTo(0.2);
    assertThat(w.weight(ev("SAVE", null), null, false))
        .isGreaterThan(w.weight(ev("LIKE", null), null, false));
    assertThat(w.weight(ev("NOT_INTERESTED", null), null, false)).isEqualTo(-3.0);
    assertThat(w.weight(ev("RATE", 5.0), null, false)).isEqualTo(1.5);
    assertThat(w.weight(ev("IMPRESSION", null), null, false)).isZero();
  }
}
