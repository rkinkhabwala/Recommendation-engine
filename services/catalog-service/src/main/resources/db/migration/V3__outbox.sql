-- Transactional outbox: every committed catalog change inserts a row in the same statement /
-- transaction; OutboxRelay publishes rows to Kafka and deletes them. Replaces the Phase 1
-- dual-write + published_seq reconciler.
CREATE TABLE outbox (
    id         BIGSERIAL PRIMARY KEY,
    item_id    TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Carry over versions that were committed but never published under the old scheme.
INSERT INTO outbox (item_id) SELECT item_id FROM items WHERE published_seq < seq;

DROP INDEX IF EXISTS items_unpublished;
ALTER TABLE items DROP COLUMN published_seq;
