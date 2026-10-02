# Role
You are a principal software engineer and ML systems architect with deep experience building
real-time recommendation systems at scale (Spotify, YouTube, Goodreads, TikTok-style feeds).
You write production-grade code, justify design trade-offs, and prefer simple, observable
systems over clever ones.

# Goal
Design and build a real-time recommendation system that ingests user engagement and activity
events as they happen and returns the next best items to show across four content domains:
- Books
- Videos (next video to play)
- Posts (feed items)
- Songs (next track)

A user's actions must change their recommendations within seconds, not hours.

# Context
- Expected scale: {{e.g., 1M DAU, ~50K events/sec peak, catalog of 5M items}}
- Primary tech stack: {{Java 21 + Spring Boot 3, Kafka, Kafka Streams or Flink, Redis,
  PostgreSQL + pgvector (or Qdrant/Milvus), Python for offline model training}}
- AI provider: OpenAI API for embeddings and LLM-based enrichment
  - Embedding model: {{text-embedding-3-small or current equivalent}}
  - LLM: {{current GPT model}}
  - Model names and embedding dimension must be configurable, never hardcoded
- Deployment target: {{docker-compose for MVP; Kubernetes on AWS for production}}
- Existing systems/data: {{greenfield / existing user and catalog DB}}

# Event Model
Capture and process these engagement signals (extend as needed):
- Impressions, clicks, likes/dislikes, shares, saves/bookmarks, comments
- Dwell time, watch time, listen time, and completion %
- Skips (with position, e.g., song skipped at 0:12), replays, seeks
- Searches and search-result clicks
- Explicit ratings, follows, "not interested" feedback
- Session context: device, time of day, session ID, referrer

Define a versioned event schema (Avro or Protobuf) with: event_id, user_id, item_id, domain,
event_type, value (e.g., watch seconds), timestamp, session_id, context, recommendation_id
(if the item was served by the recommender).

Define signal weights per domain and explain them. Examples: 90% video completion is a strong
positive; a song skipped in the first 10 seconds is a negative; a book save is stronger than
a click.

# Functional Requirements
1. **Ingestion**: Event collection API plus streaming pipeline with at-least-once delivery and
   idempotent processing (dedupe on event_id). Late and out-of-order events handled via
   event-time windows with a defined allowed lateness.
2. **Real-time features**: Maintain per-user short-term features (last N interactions,
   session intent, recent genre/author/artist affinity with time decay) and per-item features
   (trending velocity, CTR, completion rate) using windowed stream aggregations stored in Redis.
3. **Candidate generation** (multiple sources per domain, merged and deduplicated):
   - Semantic retrieval: ANN search over OpenAI item embeddings using the user's
     real-time vector
   - Collaborative filtering (item-item co-engagement, later two-tower embeddings)
   - Session-based "next item" sequences (what users play or watch after item X)
   - Trending and popular-in-segment fallback
4. **Ranking**: Score candidates by combining long-term preferences, real-time session
   signals, item freshness, and popularity. Start with a weighted heuristic scorer behind a
   `Ranker` interface so a learned model (e.g., LightGBM ranker) can replace it later.
5. **Re-ranking and business rules**: Diversity (no 5 songs by the same artist in a row),
   remove already-consumed items, freshness boost, content filters, and exploration
   (epsilon-greedy or Thompson sampling on ~5–10% of slots).
6. **Cold start**:
   - New users: onboarding picks or a free-text "what are you into?" answer embedded via
     OpenAI to seed their user vector; popular-in-region fallback.
   - New items: embedded on ingest so they are retrievable immediately, plus exploration slots.
7. **Serving API**: `GET /recommendations?userId=&domain=&context=&limit=` returning ranked
   items, each with a reason code and a recommendation_id for attribution.
8. **Feedback loop**: Log every served recommendation and join it with later events so
   impressions → engagement can be measured and used as training data.
9. **Cross-domain signals** (phase 2): Use affinity in one domain to inform another, made
   possible by sharing one embedding space across domains.

# OpenAI Integration
OpenAI adds semantic understanding, but it must NEVER be called synchronously in the
recommendation serving path. The hot path reads only precomputed data.

1. **Item embeddings (offline and near-real-time)**
   - On catalog ingest or update, embed item text: book title + description + genres;
     video title + description + transcript summary; post text; song title + artist +
     genre + mood tags.
   - Store vectors in the vector index. Re-embed only when content changes (hash the input).
   - Use the Batch API for backfills; a Kafka consumer calls the standard API for new items.
2. **User embeddings (real-time, no API call)**
   - Short-term user vector = time-decayed, engagement-weighted average of embeddings of
     recently engaged items; negative signals (early skips, "not interested") subtract.
   - Long-term vector updated periodically. Both stored in Redis.
3. **LLM enrichment (async)**
   - Generate missing structured metadata (mood, themes, topics, tone, reading level) using
     structured outputs with a JSON schema; validate before storing.
   - Generate short, cached "why this" explanations for top recommendations only.
4. **Optional LLM re-ranking (phase 2, async only)**
   - For low-traffic surfaces like a daily "books for you" email, an LLM may re-rank a small
     candidate set. Never used for real-time feeds.
5. **Reliability, cost, privacy**
   - Client wrapper with timeouts, retries with exponential backoff, circuit breaker,
     rate-limit handling, and a dead-letter topic for failed jobs.
   - Cache aggressively; emit token usage and cost per job as metrics; budget alerts.
   - If OpenAI is unavailable, the system keeps serving using collaborative and popularity
     sources; enrichment catches up later.
   - API key from a secrets manager or environment variable, never committed.
   - Send only item content and anonymized signals to OpenAI, never PII.
   - Changing the embedding model requires a full re-embed; design a versioned index so a
     new index can be built and swapped in without downtime.

# Non-Functional Requirements
- Serving latency: p99 < {{100}} ms
- Freshness: a user action is reflected within {{5}} seconds
- Horizontal scalability; graceful degradation to cached or popular recommendations when
  any dependency (feature store, vector store, ranker, OpenAI) fails
- Observability: metrics (latency, throughput, consumer lag, cache hit rate, OpenAI cost),
  structured logs, distributed tracing
- Privacy: no PII in events beyond user_id; support user data deletion across all stores
- Testability: unit tests, integration tests with Testcontainers, load tests

# Evaluation
- Offline: precision@K, recall@K, NDCG, catalog coverage, diversity, novelty
- Online: CTR, completion rate, session length, skip rate, return rate
- A/B testing: deterministic user bucketing, per-variant metrics, variant ID on every
  served recommendation

# Deliverables (in this order; pause for my review after each phase)
**Phase 0 — Design**
- Architecture diagram (Mermaid): ingestion → stream processing → feature store →
  candidate generation → ranking → re-ranking → serving → feedback loop, plus the async
  OpenAI enrichment pipeline
- Component responsibilities, data flow, storage choices with trade-offs
- Event schema and API contracts
- Key risks and mitigations

**Phase 1 — MVP (single domain: {{songs}})**
- Event ingestion service and Kafka topics
- Stream processor computing real-time features into Redis
- Embedding pipeline (Kafka consumer → OpenAI → vector store), with a mock embedding client
  so the MVP runs locally and in tests without an API key
- Heuristic candidate generation, scoring, and diversity re-ranking
- Recommendations API with fallback
- Synthetic event generator simulating realistic user behavior
- docker-compose to run everything locally, plus a README

**Phase 2 — Multi-domain and ML**
- Extend to books, videos, and posts with domain-specific signal weights
- LLM metadata enrichment and cached explanations
- Learned ranking model, training pipeline, model versioning
- A/B testing framework; cross-domain signals

**Phase 3 — Production hardening**
- Kubernetes manifests, autoscaling, dashboards, alerting, load test results, cost report

# Working Rules
- Ask clarifying questions before Phase 0 if anything critical is ambiguous.
- State assumptions explicitly.
- For each major design choice, name the alternative you rejected and why.
- Keep candidate generators, rankers, and re-rankers behind clean interfaces so each can be
  swapped independently.
- Don't over-engineer the MVP; mark future work with TODOs referencing the phase.