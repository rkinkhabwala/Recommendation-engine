package com.recsys.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.recsys.common.Topics;
import com.recsys.common.Vectors;
import com.recsys.events.v1.CatalogItem;
import com.recsys.events.v1.Domain;
import com.recsys.events.v1.EventContext;
import com.recsys.events.v1.EventType;
import com.recsys.events.v1.ItemEmbedding;
import com.recsys.events.v1.MediaProgress;
import com.recsys.events.v1.Outcome;
import com.recsys.events.v1.RecommendationAttributed;
import com.recsys.events.v1.RecommendationServed;
import com.recsys.events.v1.ServedItem;
import com.recsys.events.v1.UserDeletionRequested;
import com.recsys.events.v1.UserEvent;
import com.recsys.features.FeatureEnvelope;
import com.recsys.features.FeatureJson;
import com.recsys.features.RedisKeys;
import com.recsys.features.model.ItemStats;
import com.recsys.features.model.Neighbors;
import com.recsys.features.model.ScoredItem;
import com.recsys.features.model.TrendingList;
import com.recsys.features.model.UserShortTerm;
import com.recsys.stream.serde.StreamSerdes;
import com.recsys.stream.signals.SignalWeighers;
import com.recsys.stream.topology.OnlineMetrics;
import com.recsys.stream.topology.RecsTopology;
import com.recsys.stream.topology.TopologySettings;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecsTopologyTest {
  static final String INDEX = "items_test_4_v1";
  static final Instant T0 = Instant.parse("2026-10-02T10:00:00Z");
  static final float[] JAZZ = {1, 0, 0, 0};
  static final float[] ROCK = {0, 1, 0, 0};

  TopologyTestDriver driver;
  TestInputTopic<String, UserEvent> events;
  TestInputTopic<String, RecommendationServed> served;
  TestInputTopic<String, UserDeletionRequested> deletions;
  TestOutputTopic<String, byte[]> userFeatures;
  TestOutputTopic<String, byte[]> itemFeatures;
  TestOutputTopic<String, byte[]> trending;
  TestOutputTopic<String, UserEvent> late;
  TestOutputTopic<String, RecommendationAttributed> attributed;

  static TopologySettings settings() {
    var d = TopologySettings.defaults(INDEX);
    return new TopologySettings(
        INDEX,
        d.dedupeWindow(),
        d.maxLateness(),
        d.shortTermHalfLife(),
        d.longTermHalfLife(),
        d.affinityHalfLife(),
        d.itemStatsHalfLife(),
        d.neighborHalfLife(),
        d.recentlyPlayedWindow(),
        Duration.ZERO, // emit long-term on every update
        Duration.ZERO, // emit item features on every update
        d.trendingWindow(),
        d.trendingGrace(),
        Duration.ofSeconds(30),
        d.attributionWindow(),
        d.userKeyTtl(),
        d.itemKeyTtl(),
        d.negativeCapRatio(),
        d.coEngagementMinWeight(),
        d.maxCoEmitsPerSession(),
        d.neighborTopK(),
        1.0, // min support 1 so a single session creates neighbours
        d.trendingTopN(),
        d.affinityMaxEntries(),
        d.crossDomainHalfLife(),
        Duration.ZERO, // emit the cross-domain vector on every update
        d.consumedWindows(),
        d.consumedMax());
  }

  @BeforeEach
  void setUp() throws Exception {
    StreamSerdes avro = new StreamSerdes("mock://recs-topology-test");
    var topology =
        RecsTopology.build(settings(), SignalWeighers.defaults(), avro, OnlineMetrics.NOOP);
    Properties p = new Properties();
    p.put(StreamsConfig.APPLICATION_ID_CONFIG, "recs-test");
    p.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");
    p.put(StreamsConfig.STATE_DIR_CONFIG, Files.createTempDirectory("recs-streams").toString());
    driver = new TopologyTestDriver(topology, p, T0);

    var str = Serdes.String();
    var items =
        driver.createInputTopic(
            Topics.CATALOG_ITEMS, str.serializer(), avro.catalogItem.serializer());
    var embeddings =
        driver.createInputTopic(
            Topics.CATALOG_EMBEDDINGS, str.serializer(), avro.itemEmbedding.serializer());
    events =
        driver.createInputTopic(Topics.EVENTS_RAW, str.serializer(), avro.userEvent.serializer());
    served =
        driver.createInputTopic(Topics.RECS_SERVED, str.serializer(), avro.served.serializer());
    deletions =
        driver.createInputTopic(
            Topics.USERS_DELETION, str.serializer(), avro.deletion.serializer());
    var bytes = Serdes.ByteArray().deserializer();
    userFeatures = driver.createOutputTopic(Topics.FEATURES_USER, str.deserializer(), bytes);
    itemFeatures = driver.createOutputTopic(Topics.FEATURES_ITEM, str.deserializer(), bytes);
    trending = driver.createOutputTopic(Topics.FEATURES_TRENDING, str.deserializer(), bytes);
    late =
        driver.createOutputTopic(
            Topics.EVENTS_LATE, str.deserializer(), avro.userEvent.deserializer());
    attributed =
        driver.createOutputTopic(
            Topics.RECS_ATTRIBUTED, str.deserializer(), avro.attributed.deserializer());

    for (var song :
        List.of(
            List.of("j1", "a_jazz1", "jazz"),
            List.of("j2", "a_jazz2", "jazz"),
            List.of("r1", "a_rock", "rock"))) {
      String id = song.get(0);
      items.pipeInput(id, catalog(id, song.get(1), song.get(2)), T0);
      embeddings.pipeInput(id, embedding(id, song.get(2).equals("jazz") ? JAZZ : ROCK), T0);
    }
    // Other domains share the embedding space: a jazz-history book sits near jazz songs.
    items.pipeInput("b_jazz", catalog("b_jazz", "author1", "jazz", Domain.BOOK), T0);
    embeddings.pipeInput("b_jazz", embedding("b_jazz", JAZZ), T0);
    items.pipeInput("v_rock", catalog("v_rock", "channel1", "rock", Domain.VIDEO), T0);
    embeddings.pipeInput("v_rock", embedding("v_rock", ROCK), T0);
    items.pipeInput("p_rock", catalog("p_rock", "poster1", "rock", Domain.POST), T0);
    embeddings.pipeInput("p_rock", embedding("p_rock", ROCK), T0);
  }

  @AfterEach
  void tearDown() {
    driver.close();
  }

  static CatalogItem catalog(String id, String artist, String genre) {
    return catalog(id, artist, genre, Domain.SONG);
  }

  static CatalogItem catalog(String id, String artist, String genre, Domain domain) {
    return CatalogItem.newBuilder()
        .setItemId(id)
        .setDomain(domain)
        .setTitle(id)
        .setCreatorId(artist)
        .setCreatorName(artist)
        .setGenres(List.of(genre))
        .setMoodTags(List.of("calm"))
        .setDurationMs(200_000L)
        .setCreatedAt(T0)
        .setUpdatedAt(T0)
        .setSeq(1)
        .build();
  }

  static ItemEmbedding embedding(String id, float[] v) {
    return ItemEmbedding.newBuilder()
        .setItemId(id)
        .setDomain(Domain.SONG)
        .setIndexVersion(INDEX)
        .setModel("mock")
        .setDims(4)
        .setContentHash("h")
        .setVector(Vectors.toList(v))
        .setEmbeddedAt(T0)
        .build();
  }

  static UserEvent event(
      String eventId,
      String user,
      String item,
      EventType type,
      Double value,
      Instant ts,
      String session,
      String recId) {
    return event(eventId, user, item, type, value, ts, session, recId, Domain.SONG);
  }

  static UserEvent event(
      String eventId,
      String user,
      String item,
      EventType type,
      Double value,
      Instant ts,
      String session,
      String recId,
      Domain domain) {
    return UserEvent.newBuilder()
        .setEventId(eventId)
        .setUserId(user)
        .setItemId(item)
        .setDomain(domain)
        .setEventType(type)
        .setValue(value)
        .setEventTs(ts)
        .setReceivedTs(ts)
        .setSessionId(session)
        .setContext(EventContext.newBuilder().setCountry("US").setAutoplay(true).build())
        .setMedia(MediaProgress.newBuilder().setDurationMs(200_000L).build())
        .setRecommendationId(recId)
        .build();
  }

  void play(String user, String item, EventType type, Double value, Instant ts) {
    events.pipeInput(
        user,
        event(UUID.randomUUID().toString(), user, item, type, value, ts, "s-" + user, null),
        ts);
  }

  void act(String user, String item, Domain domain, EventType type, Double value, Instant ts) {
    events.pipeInput(
        user,
        event(UUID.randomUUID().toString(), user, item, type, value, ts, "s-" + user, null, domain),
        ts);
  }

  static <T> T data(byte[] envelope, Class<T> type) {
    return FeatureJson.read(FeatureEnvelope.decode(envelope).dataBytes(), type);
  }

  static <T> T last(List<KeyValue<String, byte[]>> records, String key, Class<T> type) {
    return records.stream()
        .filter(kv -> kv.key.equals(key))
        .reduce((a, b) -> b)
        .map(kv -> data(kv.value, type))
        .orElse(null);
  }

  @Test
  void userVectorFollowsEngagementAndDuplicatesAreDropped() {
    var e = event("e-1", "u1", "j1", EventType.PLAY_END, 195.0, T0.plusSeconds(10), "s1", null);
    events.pipeInput("u1", e, T0.plusSeconds(10));
    events.pipeInput("u1", e, T0.plusSeconds(11)); // client retry

    var out = userFeatures.readKeyValuesToList();
    assertThat(out)
        .extracting(kv -> kv.key)
        .containsExactly("u:{u1}:st:song", "u:{u1}:lt:song", "u:{u1}:x");
    UserShortTerm st = data(out.get(0).value, UserShortTerm.class);
    assertThat(Vectors.cosine(Vectors.fromFloat16(st.vector()), JAZZ)).isGreaterThan(0.99f);
    assertThat(st.indexVersion()).isEqualTo(INDEX);
    assertThat(st.consumed()).containsKey("j1");
    assertThat(st.artistAffinity()).containsKey("a_jazz1");
    assertThat(FeatureEnvelope.decode(out.get(0).value).seq()).isEqualTo(1);
  }

  @Test
  void earlySkipsPushTheVectorAway() {
    play("u2", "j1", EventType.PLAY_END, 195.0, T0.plusSeconds(1));
    play("u2", "r1", EventType.PLAY_END, 195.0, T0.plusSeconds(2));
    play("u2", "r1", EventType.SKIP, 4.0, T0.plusSeconds(3));
    play("u2", "r1", EventType.DISLIKE, null, T0.plusSeconds(4));

    UserShortTerm st =
        last(userFeatures.readKeyValuesToList(), "u:{u2}:st:song", UserShortTerm.class);
    float[] v = Vectors.fromFloat16(st.vector());
    assertThat(Vectors.cosine(v, JAZZ)).isGreaterThan(Vectors.cosine(v, ROCK));
    assertThat(st.suppressedItems()).containsExactly("r1");
    assertThat(st.recent().get(0).eventType()).isEqualTo("DISLIKE");
  }

  @Test
  void eventsBeyondAllowedLatenessAreDivertedNotApplied() {
    play(
        "u3",
        "j1",
        EventType.PLAY_START,
        null,
        T0.plus(Duration.ofHours(30))); // advances stream time
    userFeatures.readKeyValuesToList();
    play("u4", "j1", EventType.PLAY_START, null, T0.plusSeconds(5)); // 30h late

    assertThat(late.readValuesToList()).extracting(UserEvent::getUserId).containsExactly("u4");
    assertThat(userFeatures.readKeyValuesToList()).isEmpty();
  }

  @Test
  void lateButWithinGraceEventsStillCount() {
    play("u5", "j1", EventType.PLAY_START, null, T0.plus(Duration.ofHours(2)));
    play(
        "u5", "j2", EventType.LIKE, null, T0.plus(Duration.ofHours(1))); // out of order, within 24h
    UserShortTerm st =
        last(userFeatures.readKeyValuesToList(), "u:{u5}:st:song", UserShortTerm.class);
    assertThat(st.liked()).containsExactly("j2");
    assertThat(st.recent())
        .extracting(r -> r.itemId())
        .containsExactly("j1", "j2"); // sorted by time
  }

  @Test
  void deletionEmitsTombstonesForEveryUserKey() {
    play("u6", "j1", EventType.LIKE, null, T0.plusSeconds(1));
    userFeatures.readKeyValuesToList();

    deletions.pipeInput(
        "u6",
        UserDeletionRequested.newBuilder()
            .setRequestId("d1")
            .setUserId("u6")
            .setRequestedTs(T0)
            .build(),
        T0.plusSeconds(2));

    var out = userFeatures.readKeyValuesToList();
    assertThat(out)
        .extracting(kv -> kv.key)
        .containsExactlyInAnyOrderElementsOf(RedisKeys.allUserKeys("u6"));
    assertThat(out).allMatch(kv -> kv.value == null);
  }

  @Test
  void sessionsBuildNextItemAndCoEngagementNeighbours() {
    play("u7", "j1", EventType.PLAY_END, 195.0, T0.plusSeconds(1));
    play("u7", "j2", EventType.PLAY_END, 195.0, T0.plusSeconds(200));

    var out = itemFeatures.readKeyValuesToList();
    Neighbors next = last(out, RedisKeys.itemNext("j1"), Neighbors.class);
    Neighbors co = last(out, RedisKeys.itemNeighbors("j2"), Neighbors.class);
    assertThat(next.items()).extracting(ScoredItem::itemId).containsExactly("j2");
    assertThat(co.items()).extracting(ScoredItem::itemId).containsExactly("j1");
    assertThat(last(out, RedisKeys.itemNext("j2"), Neighbors.class)).isNull(); // order matters
  }

  @Test
  void crossDomainVectorTransfersTasteAndDomainsStaySeparate() {
    act("u20", "b_jazz", Domain.BOOK, EventType.RATE, 5.0, T0.plusSeconds(1));
    var out = userFeatures.readKeyValuesToList();
    assertThat(out)
        .extracting(kv -> kv.key)
        .contains("u:{u20}:st:book", "u:{u20}:x")
        .doesNotContain("u:{u20}:st:song");
    var x = last(out, RedisKeys.userCrossDomain("u20"), com.recsys.features.model.UserVector.class);
    // A 5-star jazz book makes jazz *songs* retrievable for a user with no song history.
    assertThat(Vectors.cosine(Vectors.fromFloat16(x.vector()), JAZZ)).isGreaterThan(0.99f);
  }

  @Test
  void consumedWindowsAreDomainSpecific() {
    act("u21", "j1", Domain.SONG, EventType.PLAY_START, null, T0);
    act("u21", "b_jazz", Domain.BOOK, EventType.CLICK, null, T0);
    act("u21", "j2", Domain.SONG, EventType.PLAY_START, null, T0.plus(Duration.ofHours(3)));
    act("u21", "p_rock", Domain.BOOK, EventType.SAVE, null, T0.plus(Duration.ofDays(40)));
    var out = userFeatures.readKeyValuesToList();
    // Songs are replayable: consumption expires after hours. Books are read once: kept for a year.
    assertThat(last(out, "u:{u21}:st:song", UserShortTerm.class).consumed()).containsOnlyKeys("j2");
    assertThat(last(out, "u:{u21}:st:book", UserShortTerm.class).consumed()).containsKey("b_jazz");
  }

  @Test
  void postDwellAndVideoCompletionAreWeightedPerDomain() {
    act("u22", "p_rock", Domain.POST, EventType.DWELL, 12.0, T0.plusSeconds(1));
    act("u22", "v_rock", Domain.VIDEO, EventType.PLAY_END, 190.0, T0.plusSeconds(2));
    var out = userFeatures.readKeyValuesToList();
    assertThat(last(out, "u:{u22}:st:post", UserShortTerm.class).recent().get(0).weight())
        .isEqualTo(0.3);
    assertThat(last(out, "u:{u22}:st:video", UserShortTerm.class).recent().get(0).weight())
        .isEqualTo(1.5);
  }

  @Test
  void embeddingsTopicIsConfigurableForReEmbedding() {
    var topology =
        RecsTopology.build(
            settings(),
            SignalWeighers.defaults(),
            new StreamSerdes("mock://x"),
            OnlineMetrics.NOOP,
            "catalog.embeddings.items_test_4_v2");
    String described = topology.describe().toString();
    assertThat(described)
        .contains("catalog.embeddings.items_test_4_v2")
        .doesNotContain("[catalog.embeddings.v1]");
  }

  @Test
  void catalogMetadataIsMaterializedForServing() {
    var meta =
        last(
            itemFeatures.readKeyValuesToList(),
            RedisKeys.itemMeta("j1"),
            com.recsys.features.model.ItemMeta.class);
    assertThat(meta.artistId()).isEqualTo("a_jazz1");
    assertThat(meta.genres()).containsExactly("jazz");
  }

  @Test
  void itemStatsTrackCtrAndCompletion() {
    for (int i = 0; i < 5; i++) {
      play("u8-" + i, "j1", EventType.IMPRESSION, null, T0.plusSeconds(i));
    }
    play("u8-0", "j1", EventType.PLAY_START, null, T0.plusSeconds(10));
    play("u8-0", "j1", EventType.PLAY_END, 199.0, T0.plusSeconds(210));

    ItemStats stats =
        last(itemFeatures.readKeyValuesToList(), RedisKeys.itemStats("j1"), ItemStats.class);
    assertThat(stats.ctr()).isCloseTo(2.0 / 15, org.assertj.core.api.Assertions.within(0.01));
    assertThat(stats.completionRate()).isGreaterThan(0.5);
  }

  @Test
  void trendingIsComputedPerRegionAndGlobally() {
    for (int i = 0; i < 3; i++) {
      play("u9-" + i, "j1", EventType.PLAY_START, null, T0.plusSeconds(i));
    }
    play("u9-9", "r1", EventType.PLAY_START, null, T0.plusSeconds(5));
    driver.advanceWallClockTime(Duration.ofSeconds(31));

    var out = trending.readKeyValuesToList();
    TrendingList us = last(out, RedisKeys.trending("song", "US"), TrendingList.class);
    TrendingList global = last(out, RedisKeys.trending("song", "GLOBAL"), TrendingList.class);
    assertThat(us.items()).extracting(ScoredItem::itemId).containsExactly("j1", "r1");
    assertThat(global.items().get(0).itemId()).isEqualTo("j1");
  }

  @Test
  void engagementIsAttributedToTheServedRecommendation() {
    served.pipeInput(
        "u10",
        RecommendationServed.newBuilder()
            .setRecommendationId("rec-1")
            .setUserId("u10")
            .setDomain(Domain.SONG)
            .setSurface("next_track")
            .setVariantId("control")
            .setRankerVersion("heuristic-v1")
            .setIndexVersion(INDEX)
            .setFallbackLevel("NONE")
            .setServedTs(T0)
            .setItems(
                List.of(
                    ServedItem.newBuilder()
                        .setItemId("j1")
                        .setPosition(0)
                        .setScore(0.9)
                        .setReasonCode("SIMILAR_TO_RECENT")
                        .build()))
            .build(),
        T0);
    events.pipeInput(
        "u10",
        event("e-attr", "u10", "j1", EventType.PLAY_START, null, T0.plusSeconds(3), "s10", "rec-1"),
        T0.plusSeconds(3));

    var out = attributed.readValuesToList();
    assertThat(out).hasSize(1);
    assertThat(out.get(0).getOutcome()).isEqualTo(Outcome.PLAYED);
    assertThat(out.get(0).getVariantId()).isEqualTo("control");
    assertThat(out.get(0).getPosition()).isZero();
  }
}
