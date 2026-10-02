package com.recsys.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.recsys.api.candidates.CandidateService;
import com.recsys.api.candidates.FreshItemsGenerator;
import com.recsys.api.candidates.ItemItemCfGenerator;
import com.recsys.api.candidates.NextItemGenerator;
import com.recsys.api.candidates.ScoredCandidate;
import com.recsys.api.candidates.SemanticAnnGenerator;
import com.recsys.api.candidates.TrendingGenerator;
import com.recsys.api.config.ApiProperties;
import com.recsys.api.core.FallbackLevel;
import com.recsys.api.core.ReasonCode;
import com.recsys.api.core.RecRequest;
import com.recsys.api.core.RecommendationResult;
import com.recsys.api.core.RecommendationService;
import com.recsys.api.experiment.Experiments;
import com.recsys.api.fallback.PopularCache;
import com.recsys.api.hydration.Hydrator;
import com.recsys.api.ranking.RankerRegistry;
import com.recsys.api.rerank.DiversityReRanker;
import com.recsys.api.rerank.ExplorationReRanker;
import com.recsys.api.rerank.FamiliarityCapReRanker;
import com.recsys.api.rerank.FreshnessBoostReRanker;
import com.recsys.api.rerank.HardFilterReRanker;
import com.recsys.common.Vectors;
import com.recsys.features.InMemoryFeatureStore;
import com.recsys.features.RedisKeys;
import com.recsys.features.model.Explanation;
import com.recsys.features.model.ItemMeta;
import com.recsys.features.model.ItemStats;
import com.recsys.features.model.Neighbors;
import com.recsys.features.model.RecentInteraction;
import com.recsys.features.model.ScoredItem;
import com.recsys.features.model.TrendingList;
import com.recsys.features.model.UserShortTerm;
import com.recsys.features.model.UserVector;
import com.recsys.vector.InMemoryVectorIndex;
import com.recsys.vector.IndexedItem;
import com.recsys.vector.ItemPayload;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecommendationServiceTest {
  static final String[] GENRES = {"jazz", "rock", "pop"};
  final InMemoryFeatureStore store = new InMemoryFeatureStore();
  final InMemoryVectorIndex index = new InMemoryVectorIndex();
  final Map<String, ItemMeta> catalog = new HashMap<>();
  ApiProperties props = TestProps.create();
  PopularCache popular;
  RecommendationService service;
  long now;

  static float[] genreVector(int genre, Random r) {
    float[] v = new float[8];
    v[genre] = 1;
    for (int i = 0; i < 8; i++) {
      v[i] += (float) (r.nextGaussian() * 0.15);
    }
    return Vectors.normalized(v);
  }

  /**
   * 60 songs: s_000001-20 jazz, 21-40 rock, 41-60 pop (4 artists per genre; s_000019/60 new;
   * s_000011-14 under-exposed) and 20 books b_000001-20 (jazz/rock alternating).
   */
  @BeforeEach
  void setUp() {
    now = System.currentTimeMillis();
    Random r = new Random(7);
    index.ensureCollection(TestProps.INDEX, 8, "items_current");
    List<IndexedItem> points = new ArrayList<>();
    for (int i = 1; i <= 60; i++) {
      int g = (i - 1) / 20;
      boolean fresh = i == 60 || i == 19;
      add(
          points,
          "s_%06d".formatted(i),
          "song",
          g,
          GENRES[g] + "_artist_" + (i % 4),
          fresh,
          i >= 11 && i <= 14 ? 5 : 500,
          r);
    }
    for (int i = 1; i <= 20; i++) {
      add(points, "b_%06d".formatted(i), "book", i % 2, "author_" + (i % 5), false, 500, r);
    }
    index.upsert(TestProps.INDEX, points);
    List<ScoredItem> pop = new ArrayList<>();
    for (int i = 41; i <= 50; i++) {
      pop.add(new ScoredItem("s_%06d".formatted(i), 100 - i));
    }
    store.put(RedisKeys.trending("song", "GLOBAL"), new TrendingList("song", "GLOBAL", pop, now));
    store.put(
        RedisKeys.trending("book", "GLOBAL"),
        new TrendingList(
            "book",
            "GLOBAL",
            List.of(new ScoredItem("b_000002", 5), new ScoredItem("b_000004", 4)),
            now));
    build();
  }

  void add(
      List<IndexedItem> points,
      String id,
      String domain,
      int g,
      String artist,
      boolean fresh,
      double impressions,
      Random r) {
    long ingested = fresh ? now - 3_600_000L : now - 90L * 86_400_000L;
    var meta =
        new ItemMeta(
            id,
            domain,
            "Item " + id,
            artist,
            artist,
            List.of(GENRES[g]),
            List.of("m" + g),
            200_000L,
            LocalDate.now().minusDays(fresh ? 2 : 400),
            false,
            List.of(),
            ingested);
    catalog.put(id, meta);
    store.put(RedisKeys.itemMeta(id), meta);
    store.put(
        RedisKeys.itemStats(id),
        new ItemStats(id, impressions, impressions / 10, 0.1, 0.5, 0.2, 1, 1, now));
    points.add(
        new IndexedItem(
            new ItemPayload(
                id,
                domain,
                artist,
                List.of(GENRES[g]),
                List.of("m" + g),
                false,
                List.of(),
                ingested,
                "h",
                TestProps.INDEX),
            genreVector(g, r)));
  }

  void build() {
    popular = new PopularCache(store, "/fallback/static-popular-%s.json");
    var registry = new SimpleMeterRegistry();
    var candidates =
        new CandidateService(
            List.of(
                new SemanticAnnGenerator(index, props),
                new FreshItemsGenerator(index, props),
                new ItemItemCfGenerator(store, props),
                new NextItemGenerator(store, props),
                new TrendingGenerator(store, props)),
            Executors.newVirtualThreadPerTaskExecutor(),
            registry);
    var hydrator =
        new Hydrator(
            store,
            index,
            "items_current",
            Caffeine.newBuilder().build(),
            Caffeine.newBuilder().build());
    var rankers = new RankerRegistry(props.ranking().modelDir(), registry);
    rankers.reload();
    service =
        new RecommendationService(
            store,
            candidates,
            hydrator,
            rankers,
            List.of(
                new HardFilterReRanker(),
                new FreshnessBoostReRanker(),
                new FamiliarityCapReRanker(),
                new DiversityReRanker(),
                new ExplorationReRanker()),
            popular,
            null,
            new Experiments(props.experiments()),
            props,
            CircuitBreaker.ofDefaults("redis"),
            registry,
            Clock.systemUTC());
  }

  void shortTerm(
      String userId,
      String domain,
      float[] vector,
      String indexVersion,
      List<RecentInteraction> recent,
      Map<String, Long> consumed,
      List<String> suppressed,
      Map<String, Long> suppressedArtists,
      List<String> liked) {
    store.put(
        RedisKeys.userShortTerm(userId, domain),
        new UserShortTerm(
            userId,
            domain,
            indexVersion,
            vector == null ? null : Vectors.toFloat16(vector),
            recent,
            Map.of(),
            Map.of(),
            Map.of(),
            consumed,
            liked,
            suppressed,
            suppressedArtists,
            "sess",
            Map.of(),
            recent.isEmpty() ? null : recent.get(0).itemId(),
            now));
  }

  void shortTerm(String userId, float[] vector) {
    shortTerm(
        userId,
        "song",
        vector,
        TestProps.INDEX,
        List.of(),
        Map.of(),
        List.of(),
        Map.of(),
        List.of());
  }

  RecommendationResult recommend(String userId, String domain, int limit) {
    return service.recommend(
        new RecRequest(userId, domain, "home", limit, "sess", null, "US", "ios", true));
  }

  RecommendationResult recommend(String userId, int limit) {
    return recommend(userId, "song", limit);
  }

  static List<String> genresOf(RecommendationResult r, Map<String, ItemMeta> catalog) {
    return r.items().stream().map(c -> catalog.get(c.itemId).genres().get(0)).toList();
  }

  @Test
  void realTimeVectorDrivesRetrievalAndListIsDiverse() {
    shortTerm("u1", genreVector(0, new Random(1)));
    var r = recommend("u1", 6);

    assertThat(r.fallbackLevel()).isEqualTo(FallbackLevel.NONE);
    assertThat(genresOf(r, catalog).subList(0, 5)).containsOnly("jazz");
    assertThat(r.items()).allMatch(c -> catalog.get(c.itemId).domain().equals("song"));
    assertThat(r.items().get(0).reason).isEqualTo(ReasonCode.SIMILAR_TO_RECENT);
    List<String> artists = r.items().stream().map(ScoredCandidate::artistId).toList();
    for (int i = 1; i < artists.size(); i++) {
      assertThat(artists.get(i)).isNotEqualTo(artists.get(i - 1));
      if (i >= 2) {
        assertThat(artists.get(i)).isNotEqualTo(artists.get(i - 2));
      }
    }
  }

  @Test
  void consumedRejectedAndSuppressedCreatorsNeverAppear() {
    String played = "s_000001";
    String disliked = "s_000002";
    String blockedArtist = catalog.get("s_000003").artistId();
    shortTerm(
        "u2",
        "song",
        genreVector(0, new Random(1)),
        TestProps.INDEX,
        List.of(),
        Map.of(played, now - 60_000),
        List.of(disliked),
        Map.of(blockedArtist, now + 86_400_000L),
        List.of());

    var r = recommend("u2", 20);
    assertThat(r.items()).extracting(c -> c.itemId).doesNotContain(played, disliked);
    assertThat(r.items()).extracting(ScoredCandidate::artistId).doesNotContain(blockedArtist);
  }

  @Test
  void booksStayHiddenForAYearSongsReturnAfterHours() {
    long tenDaysAgo = now - 10L * 86_400_000L;
    float[] jazz = genreVector(0, new Random(1));
    shortTerm(
        "u3",
        "book",
        jazz,
        TestProps.INDEX,
        List.of(),
        Map.of("b_000002", tenDaysAgo),
        List.of(),
        Map.of(),
        List.of("b_000004"));
    shortTerm(
        "u3",
        "song",
        jazz,
        TestProps.INDEX,
        List.of(),
        Map.of("s_000001", tenDaysAgo),
        List.of(),
        Map.of(),
        List.of());

    // books: consumed 10 days ago and liked (already read) are both excluded
    assertThat(recommend("u3", "book", 10).items())
        .extracting(c -> c.itemId)
        .doesNotContain("b_000002", "b_000004");
    // songs: a play 10 days ago is outside the 2 h window, so the track can come back
    assertThat(recommend("u3", "song", 20).items()).extracting(c -> c.itemId).contains("s_000001");
  }

  @Test
  void crossDomainTasteServesUsersNewToADomain() {
    // The user only ever engaged with rock songs; their cross-domain vector is rock.
    store.put(
        RedisKeys.userCrossDomain("u4"),
        new UserVector(
            "u4",
            TestProps.INDEX,
            Vectors.toFloat16(genreVector(1, new Random(3))),
            "cross_domain",
            now));
    var r = recommend("u4", "book", 5);
    assertThat(r.items()).allMatch(c -> catalog.get(c.itemId).domain().equals("book"));
    assertThat(r.items().get(0).reason).isEqualTo(ReasonCode.CROSS_DOMAIN);
    assertThat(genresOf(r, catalog).subList(0, 3)).containsOnly("rock");
  }

  @Test
  void coldStartUserGetsPopularWithoutDegradation() {
    var r = recommend("brand_new_user", 5);
    assertThat(r.fallbackLevel()).isEqualTo(FallbackLevel.NONE);
    assertThat(r.items()).hasSize(5);
    assertThat(r.items()).extracting(c -> c.reason).containsOnly(ReasonCode.TRENDING);
  }

  @Test
  void cachedExplanationsAreAttachedReadOnly() {
    shortTerm(
        "u5",
        "song",
        null /* no taste vector: co-engagement is the dominant signal */,
        TestProps.INDEX,
        List.of(new RecentInteraction("s_000021", "rock_artist_1", "PLAY_END", 1.0, now - 1000)),
        Map.of(),
        List.of(),
        Map.of(),
        List.of());
    store.put(
        RedisKeys.itemNeighbors("s_000021"),
        new Neighbors("s_000021", "i2i", List.of(new ScoredItem("s_000022", 1.0)), now));
    store.put(
        RedisKeys.explanation("song", "LISTENED_TOGETHER", "s_000021", "s_000022"),
        new Explanation(
            "People who enjoy Item s_000021 often pick Item s_000022 too.", "mock-llm", now));

    var r = recommend("u5", 10);
    var item =
        r.items().stream().filter(c -> c.itemId.equals("s_000022")).findFirst().orElseThrow();
    assertThat(item.explanation).startsWith("People who enjoy");
  }

  @Test
  void reasonComesFromTheStrongestContributionNotTheLargestRawScore() {
    // Trending lists are max-normalized (raw score 1.0) but a strong taste match should still
    // be explained by taste.
    shortTerm(
        "u13", genreVector(2, new Random(5))); // pop taste; s_000041-50 are trending pop songs
    var r = recommend("u13", 5);
    assertThat(r.items().get(0).reason).isEqualTo(ReasonCode.SIMILAR_TO_RECENT);
  }

  @Test
  void redisOutageServesCachedPopularAndNeverThrows() {
    popular.refresh(); // healthy refresh before the outage
    store.failing = true;
    var r = recommend("u6", 5);
    assertThat(r.fallbackLevel()).isEqualTo(FallbackLevel.CACHED_POPULAR);
    assertThat(r.items()).extracting(c -> c.itemId).startsWith("s_000041");
  }

  @Test
  void coldProcessWithRedisDownUsesStaticListPerDomain() {
    store.failing = true;
    assertThat(recommend("u7", "song", 5).fallbackLevel()).isEqualTo(FallbackLevel.STATIC);
    assertThat(recommend("u7", "video", 5).items()).allMatch(c -> c.itemId.startsWith("v_"));
  }

  @Test
  void vectorIndexOutageFallsBackToCollaborativeSources() {
    shortTerm(
        "u8",
        "song",
        genreVector(1, new Random(1)),
        TestProps.INDEX,
        List.of(new RecentInteraction("s_000021", "rock_artist_1", "PLAY_END", 1.0, now - 1000)),
        Map.of(),
        List.of(),
        Map.of(),
        List.of());
    store.put(
        RedisKeys.itemNeighbors("s_000021"),
        new Neighbors(
            "s_000021",
            "i2i",
            List.of(new ScoredItem("s_000022", 1.0), new ScoredItem("s_000023", 0.8)),
            now));
    index.failing = true;

    var r = recommend("u8", 5);
    assertThat(r.fallbackLevel()).isEqualTo(FallbackLevel.PARTIAL);
    assertThat(r.items()).extracting(c -> c.itemId).contains("s_000022", "s_000023");
  }

  @Test
  void epsilonExplorationSlotIsMarkedWithPropensity() {
    shortTerm("u9", genreVector(0, new Random(1)));
    var r = recommend("u9", 10);
    var explored = r.items().stream().filter(c -> c.explore).toList();
    assertThat(explored).hasSize(1);
    assertThat(explored.get(0).propensity).isBetween(0.0, 1.0);
    assertThat(explored.get(0).reason).isEqualTo(ReasonCode.NEW_FOR_YOU);
    assertThat(r.items().indexOf(explored.get(0))).isGreaterThanOrEqualTo(2);
  }

  @Test
  void thompsonExplorationAlsoLogsAPropensity() {
    props = TestProps.create("heuristic", "thompson", null);
    build();
    shortTerm("u10", genreVector(0, new Random(1)));
    var explored = recommend("u10", 10).items().stream().filter(c -> c.explore).toList();
    assertThat(explored).hasSize(1);
    assertThat(explored.get(0).propensity).isGreaterThan(0.0).isLessThanOrEqualTo(1.0);
  }

  @Test
  void lightgbmVariantFallsBackToHeuristicWithoutAModel() {
    props = TestProps.create("lightgbm", "epsilon", "/nonexistent");
    build();
    shortTerm("u11", genreVector(0, new Random(1)));
    assertThat(recommend("u11", 5).rankerVersion()).isEqualTo("heuristic-v1");
  }

  @Test
  void vectorsFromAnotherEmbeddingSpaceAreIgnored() {
    shortTerm(
        "u12",
        "song",
        genreVector(0, new Random(1)),
        "items_old_8_v0",
        List.of(),
        Map.of(),
        List.of(),
        Map.of(),
        List.of());
    var r = recommend("u12", 5);
    assertThat(r.items()).extracting(c -> c.reason).containsOnly(ReasonCode.TRENDING);
  }
}
