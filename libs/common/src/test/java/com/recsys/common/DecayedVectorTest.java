package com.recsys.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class DecayedVectorTest {
  private static final long HOUR = 3_600_000L;
  private static final float[] JAZZ = {1, 0, 0};
  private static final float[] ROCK = {0, 1, 0};

  @Test
  void additionIsOrderIndependent() {
    var inOrder = new DecayedVector();
    inOrder.add(0, 1.0, JAZZ, HOUR);
    inOrder.add(HOUR, 1.0, ROCK, HOUR);

    var outOfOrder = new DecayedVector();
    outOfOrder.add(HOUR, 1.0, ROCK, HOUR);
    outOfOrder.add(0, 1.0, JAZZ, HOUR); // late event

    assertThat(outOfOrder.effective(0.3)).containsExactly(inOrder.effective(0.3), within(1e-6f));
    assertThat(outOfOrder.posMass).isCloseTo(inOrder.posMass, within(1e-9));
  }

  @Test
  void recentEvidenceDominates() {
    var v = new DecayedVector();
    v.add(0, 1.0, JAZZ, HOUR);
    v.add(4 * HOUR, 1.0, ROCK, HOUR); // jazz is 4 half-lives old -> weight 1/16
    float[] e = v.effective(0.3);
    assertThat(e[1]).isGreaterThan(e[0] * 10);
  }

  @Test
  void negativeEvidenceIsCappedAndCannotFlipTaste() {
    var v = new DecayedVector();
    v.add(0, 1.0, JAZZ, HOUR);
    for (int i = 0; i < 20; i++) {
      v.add(0, -0.8, JAZZ, HOUR); // skip spree on jazz
    }
    float[] e = v.effective(0.3);
    assertThat(e[0]).isPositive(); // still leans jazz, just less
  }

  @Test
  void onlyNegativeEvidenceYieldsNoVector() {
    var v = new DecayedVector();
    v.add(0, -2.0, ROCK, HOUR);
    assertThat(v.effective(0.3)).isNull();
  }
}
