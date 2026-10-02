package com.recsys.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.recsys.api.candidates.ScoredCandidate;
import com.recsys.api.config.ApiProperties.Variant;
import com.recsys.api.core.RecContext;
import com.recsys.api.core.RecRequest;
import com.recsys.api.experiment.Bucketer;
import com.recsys.api.ranking.RankerWeights;
import com.recsys.api.rerank.DiversityReRanker;
import com.recsys.common.Deadline;
import com.recsys.features.model.ItemMeta;
import com.recsys.features.model.UserFeatures;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiversityAndBucketingTest {

  static ScoredCandidate c(String id, String artist, double score) {
    var c = new ScoredCandidate(id);
    c.meta =
        new ItemMeta(
            id,
            "song",
            id,
            artist,
            artist,
            List.of("g"),
            List.of(),
            null,
            null,
            false,
            List.of(),
            0);
    c.score = score;
    return c;
  }

  @Test
  void neverFiveInARowAndNoRepeatWithinThreeWhenAlternativesExist() {
    List<ScoredCandidate> ranked = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      ranked.add(c("a" + i, "A", 1.0 - i * 0.01)); // the top 12 are all one artist
    }
    ranked.add(c("b1", "B", 0.5));
    ranked.add(c("b2", "B", 0.49));
    ranked.add(c("c1", "C", 0.4));
    ranked.add(c("d1", "D", 0.3));
    var ctx =
        RecContext.of(
            new RecRequest("u", "song", "home", 6, null, null, null, null, true),
            Bucketer.CONTROL,
            UserFeatures.EMPTY,
            "items_x_8_v1",
            0.7,
            0,
            Deadline.in(Duration.ofSeconds(1)),
            false);

    // 4 non-A items can separate at most 6 slots (A x x A x x); beyond that the rules relax.
    var out =
        new DiversityReRanker(TestProps.create().rerank()).apply(ctx, ranked, 6).subList(0, 6);
    var artists = out.stream().map(ScoredCandidate::artistId).toList();
    for (int i = 1; i < artists.size(); i++) {
      assertThat(artists.get(i)).isNotEqualTo(artists.get(i - 1));
    }
    assertThat(artists.subList(0, 3)).doesNotHaveDuplicates();
    assertThat(java.util.Collections.frequency(artists, "A")).isEqualTo(2); // max 2 per 10 slots
    assertThat(artists.get(0)).isEqualTo("A"); // best item still first
  }

  @Test
  void bucketingIsDeterministicAndSplitsTraffic() {
    var b =
        new Bucketer(
            "salt",
            List.of(
                new Variant("control", 0, 500, RankerWeights.defaults()),
                new Variant("treatment", 500, 1000, RankerWeights.defaults())));
    assertThat(b.assign("user-42").id()).isEqualTo(b.assign("user-42").id());
    long treatment =
        java.util.stream.IntStream.range(0, 10_000)
            .filter(i -> b.assign("u" + i).id().equals("treatment"))
            .count();
    assertThat(treatment).isBetween(4_700L, 5_300L);
  }
}
