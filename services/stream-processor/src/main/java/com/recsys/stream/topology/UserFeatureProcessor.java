package com.recsys.stream.topology;

import com.recsys.common.DecayedVector;
import com.recsys.common.Vectors;
import com.recsys.features.FeatureEnvelope;
import com.recsys.features.RedisKeys;
import com.recsys.features.model.RecentInteraction;
import com.recsys.features.model.UserShortTerm;
import com.recsys.features.model.UserVector;
import com.recsys.stream.model.CoEmit;
import com.recsys.stream.model.EnrichedEvent;
import com.recsys.stream.model.EventView;
import com.recsys.stream.model.ItemProfile;
import com.recsys.stream.model.UserInput;
import com.recsys.stream.model.UserOut;
import com.recsys.stream.model.UserState;
import com.recsys.stream.signals.SignalWeigher;
import java.time.Duration;
import java.util.ArrayList;
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
 * Maintains per-user real-time features: short-term (2h half-life) and long-term (30d) taste
 * vectors, decayed artist/genre/mood affinities, recent interactions, session intent, recently
 * played and suppressed items. Emits the short-term record on every event and the long-term vector
 * at most once per interval. Also emits co-engagement observations for item-item CF and next-item.
 */
final class UserFeatureProcessor implements Processor<String, UserInput, String, UserOut> {
  private static final int RECENT_MAX = 50;
  private static final int LIKED_MAX = 200;
  private static final int SUPPRESSED_MAX = 500;
  private static final int SESSION_POSITIVES = 5;
  private static final long BROWSE_WINDOW_MS = 30_000;
  private static final int BROWSE_SKIPS = 3;
  private static final long ARTIST_SUPPRESSION_MS = Duration.ofDays(30).toMillis();
  private static final Set<String> PLAY_TYPES = Set.of("PLAY_START", "PLAY_END", "SKIP", "REPLAY");
  private static final Set<String> ENGAGEMENT_TYPES =
      Set.of("PLAY_END", "REPLAY", "LIKE", "SAVE", "SHARE", "SKIP");

  private final TopologySettings s;
  private final SignalWeigher weigher;
  private final Set<String> longTermDirty = new HashSet<>();
  private ProcessorContext<String, UserOut> ctx;
  private KeyValueStore<String, UserState> store;

  UserFeatureProcessor(TopologySettings settings, SignalWeigher weigher) {
    this.s = settings;
    this.weigher = weigher;
  }

  @Override
  public void init(ProcessorContext<String, UserOut> context) {
    this.ctx = context;
    this.store = context.getStateStore(StoreNames.USER_STATE);
    if (!s.longTermEmitInterval().isZero()) {
      // Flushes long-term vectors of users whose last update was throttled. The dirty set is
      // in-memory: after a restart a user's next event re-emits, which is acceptable staleness.
      ctx.schedule(s.longTermEmitInterval(), PunctuationType.WALL_CLOCK_TIME, this::flushLongTerm);
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
    longTermDirty.remove(userId);
    for (String key : RedisKeys.allUserKeys(userId)) {
      ctx.forward(new Record<>(key, UserOut.tombstone(key), ts));
    }
  }

  private void handle(EnrichedEvent enriched, long recordTs) {
    EventView e = enriched.event();
    ItemProfile p = enriched.profile();
    String userId = e.userId();
    UserState st = store.get(userId);
    if (st == null) {
      st = new UserState();
    }
    long ts = e.eventTs();

    if (!s.indexVersion().equals(st.indexVersion)) {
      // Embedding space changed (or first event): vectors from another space are meaningless.
      st.shortVec = new DecayedVector();
      st.longVec = new DecayedVector();
      st.indexVersion = s.indexVersion();
    }
    if (!Objects.equals(st.sessionId, e.sessionId())) {
      st.sessionId = e.sessionId();
      st.sessionStart = ts;
      st.sessionGenres.clear();
      st.sessionPositives.clear();
      st.lastPositiveItem = null;
      st.lastPositiveTs = 0;
      st.sessionCoEmits = 0;
      st.recentEarlySkips.clear();
    }

    boolean earlySkip = weigher.isEarlySkip(e);
    boolean browsing = earlySkip && Boolean.FALSE.equals(e.autoplay()) && isBrowsing(st, ts);
    Long duration = e.durationMs() != null ? e.durationMs() : p == null ? null : p.durationMs();
    double w = weigher.weight(e, duration, browsing);
    if (earlySkip) {
      st.recentEarlySkips.add(ts);
      if (st.recentEarlySkips.size() > BROWSE_SKIPS + 2) {
        st.recentEarlySkips.remove(0);
      }
    }

    float[] itemVec =
        p != null && p.vector() != null && s.indexVersion().equals(p.indexVersion())
            ? Vectors.fromFloat16(p.vector())
            : null;
    if (w != 0 && itemVec != null) {
      st.shortVec.add(ts, w, itemVec, s.shortTermHalfLife().toMillis());
      st.longVec.add(ts, w, itemVec, s.longTermHalfLife().toMillis());
      longTermDirty.add(userId);
    }

    long affHl = s.affinityHalfLife().toMillis();
    if (p != null) {
      double artistW = "FOLLOW".equals(e.eventType()) ? weigher.weights().followArtistWeight() : w;
      Features.add(st.artistAff, p.artistId(), artistW, ts, affHl);
      if (w != 0) {
        for (String g : p.genres()) {
          Features.add(st.genreAff, g, w / p.genres().size(), ts, affHl);
        }
        for (String m : p.moods()) {
          Features.add(st.moodAff, m, w / p.moods().size(), ts, affHl);
        }
      }
      if (w > 0) {
        for (String g : p.genres()) {
          st.sessionGenres.merge(g, w / p.genres().size(), Double::sum);
        }
      }
      int max = s.affinityMaxEntries();
      Features.prune(st.artistAff, max, ts, affHl);
      Features.prune(st.genreAff, max, ts, affHl);
      Features.prune(st.moodAff, max, ts, affHl);
    }

    String artistId = p == null ? null : p.artistId();
    st.recent.add(
        new RecentInteraction(e.itemId(), artistId, e.eventType(), Features.round(w), ts));
    // Events reach this processor via repartition topics, so cross-item order is not guaranteed.
    st.recent.sort(java.util.Comparator.comparingLong(RecentInteraction::ts).reversed());
    if (st.recent.size() > RECENT_MAX) {
      st.recent.subList(RECENT_MAX, st.recent.size()).clear();
    }
    st.lastEventTs = Math.max(st.lastEventTs, ts);
    st.lastReceivedTs = Math.max(st.lastReceivedTs, e.receivedTs());
    if (PLAY_TYPES.contains(e.eventType())) {
      st.recentlyPlayed.merge(e.itemId(), ts, Math::max);
    }
    long cutoff = st.lastEventTs - s.recentlyPlayedWindow().toMillis();
    st.recentlyPlayed.values().removeIf(t -> t < cutoff);

    switch (e.eventType()) {
      case "LIKE", "SAVE" -> pushFront(st.liked, e.itemId(), LIKED_MAX);
      case "DISLIKE" -> pushFront(st.suppressedItems, e.itemId(), SUPPRESSED_MAX);
      case "NOT_INTERESTED" -> {
        pushFront(st.suppressedItems, e.itemId(), SUPPRESSED_MAX);
        if (artistId != null) {
          st.suppressedArtists.put(artistId, ts + ARTIST_SUPPRESSION_MS);
        }
      }
      default -> {}
    }
    long latest = st.lastEventTs;
    st.suppressedArtists.values().removeIf(until -> until < latest);

    if (w >= s.coEngagementMinWeight() && ENGAGEMENT_TYPES.contains(e.eventType())) {
      emitCoEngagement(st, e, recordTs);
    }

    st.seq++;
    emitShortTerm(userId, st, recordTs);
    long now = ctx.currentSystemTimeMs();
    if (!st.longVec.isEmpty()
        && (st.ltSeq == 0
            || s.longTermEmitInterval().isZero()
            || now - st.lastLtEmitWallMs >= s.longTermEmitInterval().toMillis())) {
      emitLongTerm(userId, st, now, recordTs);
    }
    store.put(userId, st);
  }

  private boolean isBrowsing(UserState st, long ts) {
    long recent = st.recentEarlySkips.stream().filter(t -> ts - t <= BROWSE_WINDOW_MS).count();
    return recent + 1 >= BROWSE_SKIPS;
  }

  private void emitCoEngagement(UserState st, EventView e, long recordTs) {
    String item = e.itemId();
    if (item.equals(st.lastPositiveItem)) {
      return;
    }
    List<CoEmit> out = new ArrayList<>();
    // NEXT is order-sensitive: only emit for events newer than the previous positive one.
    boolean inOrder = e.eventTs() >= st.lastPositiveTs;
    if (st.lastPositiveItem != null && inOrder) {
      out.add(new CoEmit(st.lastPositiveItem, item, CoEmit.NEXT, 1.0, e.eventTs(), e.receivedTs()));
    }
    for (String other : st.sessionPositives) {
      if (!other.equals(item)) {
        out.add(new CoEmit(other, item, CoEmit.CO, 1.0, e.eventTs(), e.receivedTs()));
        out.add(new CoEmit(item, other, CoEmit.CO, 1.0, e.eventTs(), e.receivedTs()));
      }
    }
    // Per-session cap limits how much one user (or bot) can shape co-engagement.
    for (CoEmit c : out) {
      if (st.sessionCoEmits++ >= s.maxCoEmitsPerSession()) {
        break;
      }
      ctx.forward(new Record<>(c.fromItem(), UserOut.co(c), recordTs));
    }
    st.sessionPositives.remove(item);
    st.sessionPositives.add(item);
    if (st.sessionPositives.size() > SESSION_POSITIVES) {
      st.sessionPositives.remove(0);
    }
    if (inOrder) {
      st.lastPositiveItem = item;
      st.lastPositiveTs = e.eventTs();
    }
  }

  private void emitShortTerm(String userId, UserState st, long recordTs) {
    long now = st.lastEventTs;
    long affHl = s.affinityHalfLife().toMillis();
    float[] vec = st.shortVec.effective(s.negativeCapRatio());
    var data =
        new UserShortTerm(
            userId,
            st.indexVersion,
            vec == null ? null : Vectors.toFloat16(vec),
            List.copyOf(st.recent),
            Features.snapshot(st.artistAff, now, affHl),
            Features.snapshot(st.genreAff, now, affHl),
            Features.snapshot(st.moodAff, now, affHl),
            Map.copyOf(st.recentlyPlayed),
            List.copyOf(st.liked),
            List.copyOf(st.suppressedItems),
            Map.copyOf(st.suppressedArtists),
            st.sessionId,
            Map.copyOf(st.sessionGenres),
            st.lastPositiveItem,
            now);
    String key = RedisKeys.userShortTerm(userId);
    byte[] env =
        FeatureEnvelope.encode(st.seq, s.userKeyTtl().toSeconds(), st.lastReceivedTs, data);
    ctx.forward(new Record<>(key, UserOut.feature(key, env), recordTs));
  }

  private void emitLongTerm(String userId, UserState st, long wallNow, long recordTs) {
    float[] vec = st.longVec.effective(s.negativeCapRatio());
    if (vec == null) {
      return;
    }
    st.ltSeq++;
    st.lastLtEmitWallMs = wallNow;
    longTermDirty.remove(userId);
    var data =
        new UserVector(
            userId, st.indexVersion, Vectors.toFloat16(vec), "long_term", st.lastEventTs);
    String key = RedisKeys.userLongTerm(userId);
    byte[] env =
        FeatureEnvelope.encode(st.ltSeq, s.userKeyTtl().toSeconds(), st.lastReceivedTs, data);
    ctx.forward(new Record<>(key, UserOut.feature(key, env), recordTs));
  }

  private void flushLongTerm(long wallNow) {
    for (String userId : List.copyOf(longTermDirty)) {
      UserState st = store.get(userId);
      if (st != null && !st.longVec.isEmpty()) {
        emitLongTerm(userId, st, wallNow, Math.max(0, ctx.currentStreamTimeMs()));
        store.put(userId, st);
      }
    }
    longTermDirty.clear();
  }

  private static void pushFront(List<String> list, String id, int max) {
    list.remove(id);
    list.add(0, id);
    if (list.size() > max) {
      list.subList(max, list.size()).clear();
    }
  }
}
