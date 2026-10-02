package com.recsys.stream.topology;

import com.recsys.common.DecayedVector;
import com.recsys.common.Vectors;
import com.recsys.features.FeatureEnvelope;
import com.recsys.features.RedisKeys;
import com.recsys.features.model.RecentInteraction;
import com.recsys.features.model.UserShortTerm;
import com.recsys.features.model.UserVector;
import com.recsys.stream.model.CoEmit;
import com.recsys.stream.model.DomainState;
import com.recsys.stream.model.EnrichedEvent;
import com.recsys.stream.model.EventView;
import com.recsys.stream.model.ItemProfile;
import com.recsys.stream.model.UserInput;
import com.recsys.stream.model.UserOut;
import com.recsys.stream.model.UserState;
import com.recsys.stream.signals.SignalWeigher;
import com.recsys.stream.signals.SignalWeighers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;

/**
 * Maintains per-user, per-domain real-time features: short-term (2h half-life) and long-term (30d)
 * taste vectors, decayed creator/genre/mood affinities, recent interactions, session intent,
 * consumed and suppressed items. Also maintains one cross-domain taste vector (7d half-life) over
 * all domains: items of every domain share one embedding space, so a jazz-history book and a jazz
 * album land near each other and taste transfers across domains.
 *
 * <p>Emits the domain short-term record on every event; long-term and cross-domain vectors at most
 * once per interval; and co-engagement observations (within a domain) for item-item CF and
 * next-item.
 */
final class UserFeatureProcessor implements Processor<String, UserInput, String, UserOut> {
  private static final int RECENT_MAX = 50;
  private static final int LIKED_MAX = 200;
  private static final int SUPPRESSED_MAX = 500;
  private static final int SESSION_POSITIVES = 5;
  private static final long BROWSE_WINDOW_MS = 30_000;
  private static final int BROWSE_SKIPS = 3;
  private static final long CREATOR_SUPPRESSION_MS = Duration.ofDays(30).toMillis();
  private static final Set<String> CONSUME_TYPES =
      Set.of("PLAY_START", "PLAY_END", "SKIP", "REPLAY", "CLICK", "RATE");
  private static final Set<String> ENGAGEMENT_TYPES =
      Set.of("PLAY_END", "REPLAY", "LIKE", "SAVE", "SHARE", "SKIP", "RATE", "COMMENT", "DWELL");

  private final TopologySettings s;
  private final SignalWeighers weighers;

  /** "userId|domain" pairs whose long-term vector changed since the last emission. */
  private final Set<String> longTermDirty = new HashSet<>();

  private final Set<String> crossDirty = new HashSet<>();
  private ProcessorContext<String, UserOut> ctx;
  private KeyValueStore<String, UserState> store;

  UserFeatureProcessor(TopologySettings settings, SignalWeighers weighers) {
    this.s = settings;
    this.weighers = weighers;
  }

  @Override
  public void init(ProcessorContext<String, UserOut> context) {
    this.ctx = context;
    this.store = context.getStateStore(StoreNames.USER_STATE);
    // Flushes throttled long-term / cross-domain vectors. The dirty sets are in-memory: after a
    // restart a user's next event re-emits, which is acceptable staleness.
    if (!s.longTermEmitInterval().isZero()) {
      ctx.schedule(s.longTermEmitInterval(), PunctuationType.WALL_CLOCK_TIME, this::flushLongTerm);
    }
    if (!s.crossDomainEmitInterval().isZero()) {
      ctx.schedule(s.crossDomainEmitInterval(), PunctuationType.WALL_CLOCK_TIME, this::flushCross);
    }
  }

  @Override
  public void process(Record<String, UserInput> record) {
    UserInput in = record.value();
    if (in == null) {
      return;
    }
    if (in.isDeletion()) {
      delete(in.deletedUserId(), record.timestamp());
    } else {
      handle(in.event(), record.timestamp());
    }
  }

  private void delete(String userId, long ts) {
    store.delete(userId);
    longTermDirty.removeIf(k -> k.startsWith(userId + "|"));
    crossDirty.remove(userId);
    for (String key : RedisKeys.allUserKeys(userId)) {
      ctx.forward(new Record<>(key, UserOut.tombstone(key), ts));
    }
  }

  private void handle(EnrichedEvent enriched, long recordTs) {
    EventView e = enriched.event();
    ItemProfile p = enriched.profile();
    String userId = e.userId();
    String domain = e.domain();
    SignalWeigher weigher = weighers.get(domain);
    UserState user = store.get(userId);
    if (user == null) {
      user = new UserState();
    }
    if (!s.indexVersion().equals(user.indexVersion)) {
      // Embedding space changed (or first event): vectors from another space are meaningless.
      user.crossVec = new DecayedVector();
      user.domains
          .values()
          .forEach(
              d -> {
                d.shortVec = new DecayedVector();
                d.longVec = new DecayedVector();
              });
      user.indexVersion = s.indexVersion();
    }
    DomainState ds = user.domains.computeIfAbsent(domain, k -> new DomainState());
    long ts = e.eventTs();

    if (!Objects.equals(ds.sessionId, e.sessionId())) {
      ds.sessionId = e.sessionId();
      ds.sessionStart = ts;
      ds.sessionGenres.clear();
      ds.sessionPositives.clear();
      ds.lastPositiveItem = null;
      ds.lastPositiveTs = 0;
      ds.sessionCoEmits = 0;
      ds.recentEarlySkips.clear();
    }

    boolean earlySkip = weigher.isEarlySkip(e);
    boolean browsing = earlySkip && Boolean.FALSE.equals(e.autoplay()) && isBrowsing(ds, ts);
    Long duration = e.durationMs() != null ? e.durationMs() : p == null ? null : p.durationMs();
    double w = weigher.weight(e, duration, browsing);
    if (earlySkip) {
      ds.recentEarlySkips.add(ts);
      if (ds.recentEarlySkips.size() > BROWSE_SKIPS + 2) {
        ds.recentEarlySkips.remove(0);
      }
    }

    float[] itemVec =
        p != null && p.vector() != null && s.indexVersion().equals(p.indexVersion())
            ? Vectors.fromFloat16(p.vector())
            : null;
    if (w != 0 && itemVec != null) {
      ds.shortVec.add(ts, w, itemVec, s.shortTermHalfLife().toMillis());
      ds.longVec.add(ts, w, itemVec, s.longTermHalfLife().toMillis());
      user.crossVec.add(ts, w, itemVec, s.crossDomainHalfLife().toMillis());
      longTermDirty.add(userId + "|" + domain);
      crossDirty.add(userId);
    }

    long affHl = s.affinityHalfLife().toMillis();
    if (p != null) {
      double creatorW = "FOLLOW".equals(e.eventType()) ? weigher.weights().followArtistWeight() : w;
      Features.add(ds.artistAff, p.artistId(), creatorW, ts, affHl);
      if (w != 0) {
        for (String g : p.genres()) {
          Features.add(ds.genreAff, g, w / p.genres().size(), ts, affHl);
        }
        for (String m : p.moods()) {
          Features.add(ds.moodAff, m, w / p.moods().size(), ts, affHl);
        }
      }
      if (w > 0) {
        for (String g : p.genres()) {
          ds.sessionGenres.merge(g, w / p.genres().size(), Double::sum);
        }
      }
      int max = s.affinityMaxEntries();
      Features.prune(ds.artistAff, max, ts, affHl);
      Features.prune(ds.genreAff, max, ts, affHl);
      Features.prune(ds.moodAff, max, ts, affHl);
    }

    String creatorId = p == null ? null : p.artistId();
    ds.recent.add(
        new RecentInteraction(e.itemId(), creatorId, e.eventType(), Features.round(w), ts));
    // Events reach this processor via repartition topics, so cross-item order is not guaranteed.
    ds.recent.sort(Comparator.comparingLong(RecentInteraction::ts).reversed());
    if (ds.recent.size() > RECENT_MAX) {
      ds.recent.subList(RECENT_MAX, ds.recent.size()).clear();
    }
    ds.lastEventTs = Math.max(ds.lastEventTs, ts);
    ds.lastReceivedTs = Math.max(ds.lastReceivedTs, e.receivedTs());
    user.lastEventTs = Math.max(user.lastEventTs, ts);
    user.lastReceivedTs = Math.max(user.lastReceivedTs, e.receivedTs());
    if (CONSUME_TYPES.contains(e.eventType()) || weigher.isEngagedDwell(e)) {
      ds.consumed.merge(e.itemId(), ts, Math::max);
    }
    pruneConsumed(ds, domain);

    switch (e.eventType()) {
      case "LIKE", "SAVE" -> pushFront(ds.liked, e.itemId(), LIKED_MAX);
      case "DISLIKE" -> pushFront(ds.suppressedItems, e.itemId(), SUPPRESSED_MAX);
      case "NOT_INTERESTED" -> {
        pushFront(ds.suppressedItems, e.itemId(), SUPPRESSED_MAX);
        if (creatorId != null) {
          ds.suppressedArtists.put(creatorId, ts + CREATOR_SUPPRESSION_MS);
        }
      }
      default -> {}
    }
    long latest = ds.lastEventTs;
    ds.suppressedArtists.values().removeIf(until -> until < latest);

    if (w >= s.coEngagementMinWeight() && ENGAGEMENT_TYPES.contains(e.eventType())) {
      emitCoEngagement(ds, e, recordTs);
    }

    ds.seq++;
    emitShortTerm(userId, domain, user, ds, recordTs);
    long now = ctx.currentSystemTimeMs();
    if (!ds.longVec.isEmpty()
        && (ds.ltSeq == 0
            || s.longTermEmitInterval().isZero()
            || now - ds.lastLtEmitWallMs >= s.longTermEmitInterval().toMillis())) {
      emitLongTerm(userId, domain, user, ds, now, recordTs);
    }
    if (!user.crossVec.isEmpty()
        && (user.xSeq == 0
            || s.crossDomainEmitInterval().isZero()
            || now - user.lastXEmitWallMs >= s.crossDomainEmitInterval().toMillis())) {
      emitCross(userId, user, now, recordTs);
    }
    store.put(userId, user);
  }

  private void pruneConsumed(DomainState ds, String domain) {
    long cutoff = ds.lastEventTs - s.consumedWindow(domain).toMillis();
    ds.consumed.values().removeIf(t -> t < cutoff);
    if (ds.consumed.size() > s.consumedMax()) {
      var keep =
          ds.consumed.entrySet().stream()
              .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
              .limit(s.consumedMax())
              .map(Map.Entry::getKey)
              .toList();
      ds.consumed.keySet().retainAll(keep);
    }
  }

  private boolean isBrowsing(DomainState ds, long ts) {
    long recent = ds.recentEarlySkips.stream().filter(t -> ts - t <= BROWSE_WINDOW_MS).count();
    return recent + 1 >= BROWSE_SKIPS;
  }

  private void emitCoEngagement(DomainState ds, EventView e, long recordTs) {
    String item = e.itemId();
    if (item.equals(ds.lastPositiveItem)) {
      return;
    }
    List<CoEmit> out = new ArrayList<>();
    // NEXT is order-sensitive: only emit for events newer than the previous positive one.
    boolean inOrder = e.eventTs() >= ds.lastPositiveTs;
    if (ds.lastPositiveItem != null && inOrder) {
      out.add(new CoEmit(ds.lastPositiveItem, item, CoEmit.NEXT, 1.0, e.eventTs(), e.receivedTs()));
    }
    for (String other : ds.sessionPositives) {
      if (!other.equals(item)) {
        out.add(new CoEmit(other, item, CoEmit.CO, 1.0, e.eventTs(), e.receivedTs()));
        out.add(new CoEmit(item, other, CoEmit.CO, 1.0, e.eventTs(), e.receivedTs()));
      }
    }
    // Per-session cap limits how much one user (or bot) can shape co-engagement.
    for (CoEmit c : out) {
      if (ds.sessionCoEmits++ >= s.maxCoEmitsPerSession()) {
        break;
      }
      ctx.forward(new Record<>(c.fromItem(), UserOut.co(c), recordTs));
    }
    ds.sessionPositives.remove(item);
    ds.sessionPositives.add(item);
    if (ds.sessionPositives.size() > SESSION_POSITIVES) {
      ds.sessionPositives.remove(0);
    }
    if (inOrder) {
      ds.lastPositiveItem = item;
      ds.lastPositiveTs = e.eventTs();
    }
  }

  private void emitShortTerm(
      String userId, String domain, UserState user, DomainState ds, long recordTs) {
    long now = ds.lastEventTs;
    long affHl = s.affinityHalfLife().toMillis();
    float[] vec = ds.shortVec.effective(s.negativeCapRatio());
    var data =
        new UserShortTerm(
            userId,
            domain,
            user.indexVersion,
            vec == null ? null : Vectors.toFloat16(vec),
            List.copyOf(ds.recent),
            Features.snapshot(ds.artistAff, now, affHl),
            Features.snapshot(ds.genreAff, now, affHl),
            Features.snapshot(ds.moodAff, now, affHl),
            Map.copyOf(ds.consumed),
            List.copyOf(ds.liked),
            List.copyOf(ds.suppressedItems),
            Map.copyOf(ds.suppressedArtists),
            ds.sessionId,
            Map.copyOf(ds.sessionGenres),
            ds.lastPositiveItem,
            now);
    String key = RedisKeys.userShortTerm(userId, domain);
    byte[] env =
        FeatureEnvelope.encode(ds.seq, s.userKeyTtl().toSeconds(), ds.lastReceivedTs, data);
    ctx.forward(new Record<>(key, UserOut.feature(key, env), recordTs));
  }

  private void emitLongTerm(
      String userId, String domain, UserState user, DomainState ds, long wallNow, long recordTs) {
    float[] vec = ds.longVec.effective(s.negativeCapRatio());
    if (vec == null) {
      return;
    }
    ds.ltSeq++;
    ds.lastLtEmitWallMs = wallNow;
    longTermDirty.remove(userId + "|" + domain);
    var data =
        new UserVector(
            userId, user.indexVersion, Vectors.toFloat16(vec), "long_term", ds.lastEventTs);
    String key = RedisKeys.userLongTerm(userId, domain);
    byte[] env =
        FeatureEnvelope.encode(ds.ltSeq, s.userKeyTtl().toSeconds(), ds.lastReceivedTs, data);
    ctx.forward(new Record<>(key, UserOut.feature(key, env), recordTs));
  }

  private void emitCross(String userId, UserState user, long wallNow, long recordTs) {
    float[] vec = user.crossVec.effective(s.negativeCapRatio());
    if (vec == null) {
      return;
    }
    user.xSeq++;
    user.lastXEmitWallMs = wallNow;
    crossDirty.remove(userId);
    var data =
        new UserVector(
            userId, user.indexVersion, Vectors.toFloat16(vec), "cross_domain", user.lastEventTs);
    String key = RedisKeys.userCrossDomain(userId);
    byte[] env =
        FeatureEnvelope.encode(user.xSeq, s.userKeyTtl().toSeconds(), user.lastReceivedTs, data);
    ctx.forward(new Record<>(key, UserOut.feature(key, env), recordTs));
  }

  private void flushLongTerm(long wallNow) {
    long ts = Math.max(0, ctx.currentStreamTimeMs());
    for (String key : List.copyOf(longTermDirty)) {
      int bar = key.lastIndexOf('|');
      String userId = key.substring(0, bar);
      String domain = key.substring(bar + 1);
      UserState user = store.get(userId);
      DomainState ds = user == null ? null : user.domains.get(domain);
      if (ds != null && !ds.longVec.isEmpty()) {
        emitLongTerm(userId, domain, user, ds, wallNow, ts);
        store.put(userId, user);
      }
    }
    longTermDirty.clear();
  }

  private void flushCross(long wallNow) {
    long ts = Math.max(0, ctx.currentStreamTimeMs());
    for (String userId : List.copyOf(crossDirty)) {
      UserState user = store.get(userId);
      if (user != null && !user.crossVec.isEmpty()) {
        emitCross(userId, user, wallNow, ts);
        store.put(userId, user);
      }
    }
    crossDirty.clear();
  }

  private static void pushFront(List<String> list, String id, int max) {
    list.remove(id);
    list.add(0, id);
    if (list.size() > max) {
      list.subList(max, list.size()).clear();
    }
  }
}
