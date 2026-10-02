package com.recsys.stream.topology;

import com.recsys.common.Topics;
import com.recsys.events.v1.CatalogItem;
import com.recsys.events.v1.ItemEmbedding;
import com.recsys.events.v1.RecommendationAttributed;
import com.recsys.features.FeatureEnvelope;
import com.recsys.features.RedisKeys;
import com.recsys.stream.model.CoEmit;
import com.recsys.stream.model.Deduped;
import com.recsys.stream.model.EnrichedEvent;
import com.recsys.stream.model.EventView;
import com.recsys.stream.model.ItemProfile;
import com.recsys.stream.model.ItemStatsState;
import com.recsys.stream.model.NeighborState;
import com.recsys.stream.model.PartialTop;
import com.recsys.stream.model.ServedRef;
import com.recsys.stream.model.TrendingBuckets;
import com.recsys.stream.model.TrendingMergeState;
import com.recsys.stream.model.UserInput;
import com.recsys.stream.model.UserOut;
import com.recsys.stream.model.UserState;
import com.recsys.stream.model.WindowCount;
import com.recsys.stream.serde.JsonSerde;
import com.recsys.stream.serde.StreamSerdes;
import com.recsys.stream.signals.SignalWeigher;
import com.recsys.stream.signals.SignalWeighers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Branched;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.JoinWindows;
import org.apache.kafka.streams.kstream.Joined;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Named;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.Repartitioned;
import org.apache.kafka.streams.kstream.StreamJoined;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.Stores;
import org.apache.kafka.streams.state.WindowStore;

/**
 * The real-time feature topology (docs/architecture.md §3.3):
 *
 * <pre>
 * events.raw.v1 ─ dedupe/lateness ─┬─ late ───────────────────────────► events.late.v1
 *                                  ├─ by item ⋈ item-profiles ─┬─ item stats ─► features.item.v1
 *                                  │                           ├─ windowed plays ─ top-K ─ merge ─► features.trending.v1
 *                                  │                           └─ by user (+ deletions) ─ user features ─┬─► features.user.v1
 *                                  │                                                     co-engagement ─┴─ by item ─► features.item.v1
 *                                  └─ with recommendation_id ⋈ recs.served.v1 ─────────► recs.attributed.v1
 * </pre>
 *
 * Every feature output is keyed by its Redis key and carries a per-key seq; the feature-writer
 * applies it to Redis with compare-and-set (exactly-once stops at Kafka).
 */
public final class RecsTopology {
  private RecsTopology() {}

  public static Topology build(
      TopologySettings s, SignalWeighers weighers, StreamSerdes avro, OnlineMetrics metrics) {
    return build(s, weighers, avro, metrics, Topics.CATALOG_EMBEDDINGS);
  }

  /**
   * @param embeddingsTopic per-index embeddings topic (a re-embed writes a new topic)
   */
  public static Topology build(
      TopologySettings s,
      SignalWeighers weighers,
      StreamSerdes avro,
      OnlineMetrics metrics,
      String embeddingsTopic) {
    StreamsBuilder b = new StreamsBuilder();
    Serde<String> str = Serdes.String();
    Serde<byte[]> bytes = Serdes.ByteArray();
    Serde<EventView> eventSerde = JsonSerde.of(EventView.class);
    Serde<ItemProfile> profileSerde = JsonSerde.of(ItemProfile.class);

    b.addStateStore(
        Stores.windowStoreBuilder(
            Stores.persistentWindowStore(
                StoreNames.DEDUPE, s.dedupeWindow(), s.dedupeWindow(), false),
            str,
            Serdes.Long()));
    b.addStateStore(kv(StoreNames.USER_STATE, JsonSerde.of(UserState.class)));
    b.addStateStore(kv(StoreNames.ITEM_STATS, JsonSerde.of(ItemStatsState.class)));
    b.addStateStore(kv(StoreNames.NEIGHBORS, JsonSerde.of(NeighborState.class)));
    b.addStateStore(kv(StoreNames.TRENDING_BUCKETS, JsonSerde.of(TrendingBuckets.class)));
    b.addStateStore(kv(StoreNames.TRENDING_MERGE, JsonSerde.of(TrendingMergeState.class)));

    // ---- item profiles: catalog metadata ⟕ current-index embeddings
    KTable<String, CatalogItem> items =
        b.table(
            Topics.CATALOG_ITEMS, Consumed.with(str, avro.catalogItem).withName("catalog-items"));
    KTable<String, ItemEmbedding> embeddings =
        b.table(
                embeddingsTopic,
                Consumed.with(str, avro.itemEmbedding).withName("catalog-embeddings"))
            .filter((id, e) -> s.indexVersion().equals(e.getIndexVersion()));
    KTable<String, ItemProfile> profiles =
        items.leftJoin(
            embeddings,
            ItemProfile::of,
            Materialized.<String, ItemProfile, KeyValueStore<Bytes, byte[]>>as(
                    StoreNames.ITEM_PROFILES)
                .withKeySerde(str)
                .withValueSerde(profileSerde));

    // ---- serving metadata: catalog → i:{id}:meta (Redis stays rebuildable from compacted topics)
    items
        .toStream(Named.as("catalog-meta"))
        .map(
            (id, item) ->
                KeyValue.pair(
                    RedisKeys.itemMeta(id),
                    item == null
                        ? null
                        : FeatureEnvelope.encode(item.getSeq(), 0, 0, CatalogMeta.of(item))))
        .to(Topics.FEATURES_ITEM, Produced.with(str, bytes));

    // ---- ingest: dedupe + lateness
    KStream<String, Deduped> deduped =
        b.stream(Topics.EVENTS_RAW, Consumed.with(str, avro.userEvent).withName("events-raw"))
            .process(
                () -> new DedupeProcessor(s.dedupeWindow(), s.maxLateness()),
                Named.as("dedupe"),
                StoreNames.DEDUPE);
    var byLateness =
        deduped
            .split(Named.as("events-"))
            .branch((k, v) -> v.late(), Branched.as("late"))
            .defaultBranch(Branched.as("ontime"));
    byLateness
        .get("events-late")
        .mapValues(Deduped::event)
        .to(Topics.EVENTS_LATE, Produced.with(str, avro.userEvent));
    KStream<String, EventView> events =
        byLateness.get("events-ontime").mapValues(d -> EventView.of(d.event()));

    // ---- enrich with item profile (repartition by item id)
    KStream<String, EnrichedEvent> byItem =
        events
            .filter((k, v) -> v.itemId() != null)
            .selectKey((k, v) -> v.itemId())
            .leftJoin(
                profiles,
                EnrichedEvent::new,
                Joined.<String, EventView, ItemProfile>as("events-by-item")
                    .withKeySerde(str)
                    .withValueSerde(eventSerde)
                    .withOtherValueSerde(profileSerde));

    // ---- item statistics
    byItem
        .process(
            () -> new ItemStatsProcessor(s, weighers),
            Named.as("item-stats"),
            StoreNames.ITEM_STATS)
        .to(Topics.FEATURES_ITEM, Produced.with(str, bytes));

    // ---- trending: event-time windows with grace, then two-stage top-K
    byItem
        .filter(
            (k, v) ->
                weighers.get(v.event().domain()).classify(v.event(), v.event().durationMs())
                    == SignalWeigher.Kind.START)
        .flatMap((k, v) -> trendingKeys(v.event()))
        .groupByKey(Grouped.with("trending-plays", str, str))
        .windowedBy(TimeWindows.ofSizeAndGrace(s.trendingWindow(), s.trendingGrace()))
        .count(
            Materialized.<String, Long, WindowStore<Bytes, byte[]>>as(StoreNames.TRENDING_COUNTS)
                .withKeySerde(str)
                .withValueSerde(Serdes.Long()))
        .toStream()
        .map(
            (wk, count) -> {
              String key = wk.key();
              int i = key.lastIndexOf('|');
              return KeyValue.pair(
                  key,
                  new WindowCount(
                      key.substring(0, i), key.substring(i + 1), wk.window().start(), count));
            })
        .process(
            () -> new TrendingProcessor(s), Named.as("trending-local"), StoreNames.TRENDING_BUCKETS)
        .repartition(
            Repartitioned.<String, PartialTop>as("trending-partials")
                .withKeySerde(str)
                .withValueSerde(JsonSerde.of(PartialTop.class)))
        .process(
            () -> new TrendingMergeProcessor(s),
            Named.as("trending-merge"),
            StoreNames.TRENDING_MERGE)
        .to(Topics.FEATURES_TRENDING, Produced.with(str, bytes));

    // ---- user features (repartition back to user id, merged with deletion requests)
    KStream<String, UserInput> userEvents =
        byItem.selectKey((k, v) -> v.event().userId()).mapValues(UserInput::event);
    KStream<String, UserInput> deletions =
        b.stream(
                Topics.USERS_DELETION, Consumed.with(str, avro.deletion).withName("users-deletion"))
            .mapValues(d -> UserInput.deletion(d.getUserId()));
    KStream<String, UserOut> userOut =
        userEvents
            .merge(deletions)
            .repartition(
                Repartitioned.<String, UserInput>as("user-input")
                    .withKeySerde(str)
                    .withValueSerde(JsonSerde.of(UserInput.class)))
            .process(
                () -> new UserFeatureProcessor(s, weighers),
                Named.as("user-features"),
                StoreNames.USER_STATE);
    var userBranches =
        userOut
            .split(Named.as("user-out-"))
            .branch((k, v) -> v.isCo(), Branched.as("co"))
            .defaultBranch(Branched.as("features"));
    userBranches
        .get("user-out-features")
        .mapValues(UserOut::envelope) // null envelope = tombstone (deletion)
        .to(Topics.FEATURES_USER, Produced.with(str, bytes));

    // ---- co-engagement → item-item CF and session next-item
    userBranches
        .get("user-out-co")
        .map((k, v) -> KeyValue.pair(v.co().fromItem(), v.co()))
        .repartition(
            Repartitioned.<String, CoEmit>as("co-engagement")
                .withKeySerde(str)
                .withValueSerde(JsonSerde.of(CoEmit.class)))
        .process(() -> new NeighborProcessor(s), Named.as("item-neighbors"), StoreNames.NEIGHBORS)
        .to(Topics.FEATURES_ITEM, Produced.with(str, bytes));

    // ---- feedback loop: attribute engagement to served recommendations
    Attribution attribution = new Attribution(weighers);
    KStream<String, ServedRef> served =
        b.stream(Topics.RECS_SERVED, Consumed.with(str, avro.served).withName("recs-served"))
            .peek((k, v) -> metrics.served(v))
            .flatMap(
                (k, v) -> {
                  List<KeyValue<String, ServedRef>> out = new ArrayList<>();
                  for (var i : v.getItems()) {
                    out.add(
                        KeyValue.pair(
                            v.getRecommendationId() + "|" + i.getItemId(),
                            new ServedRef(
                                v.getRecommendationId(),
                                i.getItemId(),
                                i.getPosition(),
                                v.getUserId(),
                                v.getVariantId(),
                                i.getExplore(),
                                v.getServedTs().toEpochMilli())));
                  }
                  return out;
                });
    events
        .filter((k, v) -> v.recommendationId() != null && v.itemId() != null)
        .selectKey((k, v) -> v.recommendationId() + "|" + v.itemId())
        .join(
            served,
            attribution::join,
            JoinWindows.ofTimeDifferenceAndGrace(s.attributionWindow(), Duration.ofMinutes(5)),
            StreamJoined.<String, EventView, ServedRef>with(
                    str, eventSerde, JsonSerde.of(ServedRef.class))
                .withName("attribution")
                .withStoreName("attribution"))
        .filter((k, v) -> v != null)
        .peek((k, v) -> metrics.outcome(v))
        .selectKey((k, v) -> v.getRecommendationId())
        .to(
            Topics.RECS_ATTRIBUTED,
            Produced.<String, RecommendationAttributed>with(str, avro.attributed));

    return b.build();
  }

  private static List<KeyValue<String, String>> trendingKeys(EventView e) {
    String domainKey = e.domain();
    List<KeyValue<String, String>> out = new ArrayList<>(2);
    out.add(KeyValue.pair(domainKey + "|GLOBAL|" + e.itemId(), e.itemId()));
    if (e.country() != null) {
      out.add(KeyValue.pair(domainKey + "|" + e.country() + "|" + e.itemId(), e.itemId()));
    }
    return out;
  }

  private static <V> org.apache.kafka.streams.state.StoreBuilder<KeyValueStore<String, V>> kv(
      String name, Serde<V> serde) {
    return Stores.keyValueStoreBuilder(
        Stores.persistentKeyValueStore(name), Serdes.String(), serde);
  }
}
