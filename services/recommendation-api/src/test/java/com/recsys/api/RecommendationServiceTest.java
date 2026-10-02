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
import com.recsys.api.experiment.Bucketer;
import com.recsys.api.fallback.PopularCache;
import com.recsys.api.hydration.Hydrator;
import com.recsys.api.ranking.HeuristicRanker;
import com.recsys.api.rerank.DiversityReRanker;
import com.recsys.api.rerank.ExplorationReRanker;
import com.recsys.api.rerank.FamiliarityCapReRanker;
import com.recsys.api.rerank.FreshnessBoostReRanker;
import com.recsys.api.rerank.HardFilterReRanker;
import com.recsys.common.Vectors;
import com.recsys.features.InMemoryFeatureStore;
import com.recsys.features.RedisKeys;
import com.recsys.features.model.ItemMeta;
import com.recsys.features.model.Neighbors;
import com.recsys.features.model.RecentInteraction;
import com.recsys.features.model.ScoredItem;
import com.recsys.features.model.TrendingList;
import com.recsys.features.model.UserShortTerm;
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
  final ApiProperties props = TestProps.create();
  final Map<String, ItemMeta> catalog = new HashMap<>();
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

  /** 60 songs: s_000001-20 jazz, 21-40 rock, 41-60 pop; 4 artists per genre; s_000060 is new. */
  @BeforeEach
  void setUp() {
    now = System.currentTimeMillis();
    Random r = new Random(7);
    index.ensureCollection(TestProps.INDEX, 8, "items_current");
    List<IndexedItem> points = new ArrayList<>();
    for (int i = 1; i <= 60; i++) {
      String id = "s_%06d".formatted(i);
      int g = (i - 1) / 20;
      String artist = GENRES[g] + "_artist_" + (i % 4);
      boolean fresh = i == 60 || i == 19;
      long ingested = fresh ? now - 3_600_000L : now - 90L * 86_400_000L;
      var meta =
          new ItemMeta(
              id,
              "song",
              "Song " + i,
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
      // Most items are well exposed; s_000011-14 are under-exposed (exploration candidates).
      double impressions = i >= 11 && i <= 14 ? 5 : 500;
      store.put(
          RedisKeys.itemStats(id),
          new com.recsys.features.model.ItemStats(
              id, impressions, impressions / 10, 0.1, 0.5, 0.2, 1, 1, now));
      points.add(
          new IndexedItem(
              new ItemPayload(
                  id,
                  "song",
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
    index.upsert(TestProps.INDEX, points);
    List<ScoredItem> pop = new ArrayList<>();
    for (int i = 41; i <= 50; i++) {
      pop.add(new ScoredItem("s_%06d".formatted(i), 100 - i));
    }
    store.put(RedisKeys.trending("song", "GLOBAL"), new TrendingList("song", "GLOBAL", pop, now));
    popular = new PopularCache(store, "/fallback/static-popular-song.json");
    var exec = Executors.newVirtualThreadPerTaskExecutor();
    var registry = new SimpleMeterRegistry();
    var candidates =
        new CandidateService(
            List.of(
                new SemanticAnnGenerator(index, props),
                new FreshItemsGenerator(index, props),
                new ItemItemCfGenerator(store, props),
                new NextItemGenerator(store, props),
                new TrendingGenerator(store, props)),
            exec,
            registry);
    var hydrator =
        new Hydrator(
            store,
            index,
            "items_current",
            Caffeine.newBuilder().build(),
            Caffeine.newBuilder().build());
    var rr = props.rerank();
    service =
        new RecommendationService(
            store,
            candidates,
            hydrator,
            new HeuristicRanker(),
            List.of(
                new HardFilterReRanker(rr),
                new FreshnessBoostReRanker(rr),
                new FamiliarityCapReRanker(rr),
                new DiversityReRanker(rr),
                new ExplorationReRanker(rr)),
            popular,
            null,
            new Bucketer("test", props.experiments().variants()),
            props,
            CircuitBreaker.ofDefaults("redis"),
            registry,
            Clock.systemUTC());
  }

  void shortTerm(
      String userId,
      float[] vector,
      String indexVersion,
      List<RecentInteraction> recent,
      Map<String, Long> recentlyPlayed,
      List<String> suppressed,
      Map<String, Long> suppressedArtists) {
    store.put(
        RedisKeys.userShortTerm(userId),
        new UserShortTerm(
            userId,
            indexVersion,
            vector == null ? null : Vectors.toFloat16(vector),
            recent,
            Map.of(),
            Map.of(),
            Map.of(),
            recentlyPlayed,
            List.of(),
            suppressed,
            suppressedArtists,
            "sess",
            Map.of(),
            recent.isEmpty() ? null : recent.get(0).itemId(),
            now));
  }

  RecommendationResult recommend(String userId, int limit) {
    return service.recommend(
        new RecRequest(userId, "song", "home", limit, "sess", null, "US", "ios", true));
  }

  static List<String> genresOf(RecommendationResult r, Map<String, ItemMeta> catalog) {
    return r.items().stream().map(c -> catalog.get(c.itemId).genres().get(0)).toList();
  }

  @Test
  void realTimeVectorDrivesRetrievalAndListIsDiverse() {
    shortTerm(
        "u1",
        genreVector(0, new Random(1)),
        TestProps.INDEX,
        List.of(),
        Map.of(),
        List.of(),
        Map.of());
    var r = recommend("u1", 6);

    assertThat(r.fallbackLevel()).isEqualTo(FallbackLevel.NONE);
    assertThat(genresOf(r, catalog).subList(0, 5)).containsOnly("jazz");
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
  void consumedRejectedAndSuppressedArtistsNeverAppear() {
    String played = "s_000001";
    String disliked = "s_000002";
    String blockedArtist = catalog.get("s_000003").artistId();
    shortTerm(
        "u2",
        genreVector(0, new Random(1)),
        TestProps.INDEX,
        List.of(),
        Map.of(played, now - 60_000),
        List.of(disliked),
        Map.of(blockedArtist, now + 86_400_000L));

    var r = recommend("u2", 20);
    assertThat(r.items()).extracting(c -> c.itemId).doesNotContain(played, disliked);
    assertThat(r.items()).extracting(ScoredCandidate::artistId).doesNotContain(blockedArtist);
  }

  @Test
  void coldStartUserGetsPopularWithoutDegradation() {
    var r = recommend("brand_new_user", 5);
    assertThat(r.fallbackLevel()).isEqualTo(FallbackLevel.NONE);
    assertThat(r.items()).hasSize(5);
    assertThat(r.items()).extracting(c -> c.reason).containsOnly(ReasonCode.TRENDING);
  }

  @Test
  void redisOutageServesCachedPopularAndNeverThrows() {
    popular.refresh(); // healthy refresh before the outage
    store.failing = true;
    var r = recommend("u3", 5);
    assertThat(r.fallbackLevel()).isEqualTo(FallbackLevel.CACHED_POPULAR);
    assertThat(r.items()).extracting(c -> c.itemId).startsWith("s_000041");
  }

  @Test
  void coldProcessWithRedisDownUsesStaticList() {
    store.failing = true;
    var r = recommend("u4", 5);
    assertThat(r.fallbackLevel()).isEqualTo(FallbackLevel.STATIC);
    assertThat(r.items()).hasSize(5);
  }

  @Test
  void vectorIndexOutageFallsBackToCollaborativeSources() {
    shortTerm(
        "u5",
        genreVector(1, new Random(1)),
        TestProps.INDEX,
        List.of(new RecentInteraction("s_000021", "rock_artist_1", "PLAY_END", 1.0, now - 1000)),
        Map.of(),
        List.of(),
        Map.of());
    store.put(
        RedisKeys.itemNeighbors("s_000021"),
        new Neighbors(
            "s_000021",
            "i2i",
            List.of(new ScoredItem("s_000022", 1.0), new ScoredItem("s_000023", 0.8)),
            now));
    index.failing = true;

    var r = recommend("u5", 5);
    assertThat(r.fallbackLevel()).isEqualTo(FallbackLevel.PARTIAL);
    assertThat(r.items()).extracting(c -> c.itemId).contains("s_000022", "s_000023");
    assertThat(r.items())
        .filteredOn(c -> c.itemId.equals("s_000022"))
        .first()
        .satisfies(
            c -> {
              assertThat(c.reason).isEqualTo(ReasonCode.LISTENED_TOGETHER);
              assertThat(c.seedItemId).isEqualTo("s_000021");
            });
  }

  @Test
  void explorationSlotIsMarkedWithPropensity() {
    shortTerm(
        "u6",
        genreVector(0, new Random(1)),
        TestProps.INDEX,
        List.of(),
        Map.of(),
        List.of(),
        Map.of());
    var r = recommend("u6", 10);
    var explored = r.items().stream().filter(c -> c.explore).toList();
    assertThat(explored).hasSize(1);
    assertThat(explored.get(0).propensity).isBetween(0.0, 1.0);
    assertThat(explored.get(0).reason).isEqualTo(ReasonCode.NEW_FOR_YOU);
    assertThat(r.items().indexOf(explored.get(0))).isGreaterThanOrEqualTo(2);
  }

  @Test
  void vectorsFromAnotherEmbeddingSpaceAreIgnored() {
    shortTerm(
        "u7",
        genreVector(0, new Random(1)),
        "items_old_8_v0",
        List.of(),
        Map.of(),
        List.of(),
        Map.of());
    var r = recommend("u7", 5);
    assertThat(r.items()).extracting(c -> c.reason).containsOnly(ReasonCode.TRENDING);
  }
}
