-- Catalog metadata (source of truth). Vectors live in Qdrant, never here.
CREATE TABLE items (
    item_id           TEXT PRIMARY KEY,
    domain            TEXT        NOT NULL,
    title             TEXT        NOT NULL,
    creator_id        TEXT        NOT NULL,
    creator_name      TEXT        NOT NULL,
    genres            TEXT[]      NOT NULL DEFAULT '{}',
    mood_tags         TEXT[]      NOT NULL DEFAULT '{}',
    duration_ms       BIGINT,
    release_date      DATE,
    explicit          BOOLEAN     NOT NULL DEFAULT FALSE,
    available_regions TEXT[]      NOT NULL DEFAULT '{}',
    description       TEXT,
    deleted           BOOLEAN     NOT NULL DEFAULT FALSE,
    seq               BIGINT      NOT NULL DEFAULT 1,
    published_seq     BIGINT      NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Reconciler scans for rows whose latest version has not been published yet.
CREATE INDEX items_unpublished ON items (item_id) WHERE published_seq < seq;
CREATE INDEX items_domain ON items (domain);
