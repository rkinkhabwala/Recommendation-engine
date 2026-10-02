package com.recsys.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.recsys.features.FeatureStoreException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class BreakerPolicyTest {
  @Test
  void ownDeadlineMissesDoNotTripTheBreaker() {
    assertThat(
            ServingConfig.isDependencyFailure(
                new FeatureStoreException("timed out", new TimeoutException())))
        .isFalse();
    assertThat(
            ServingConfig.isDependencyFailure(
                new FeatureStoreException("refused", new java.net.ConnectException())))
        .isTrue();
  }
}
