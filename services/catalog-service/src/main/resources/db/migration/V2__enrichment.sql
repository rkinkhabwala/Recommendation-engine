-- Phase 2: multi-domain content fields and LLM enrichment.
-- Enrichment lives in separate columns so a catalog reload (curated data) never wipes it, and
-- curated values always win over enriched ones.
ALTER TABLE items ADD COLUMN transcript_summary TEXT;
ALTER TABLE items ADD COLUMN enriched_moods     TEXT[] NOT NULL DEFAULT '{}';
ALTER TABLE items ADD COLUMN themes             TEXT[] NOT NULL DEFAULT '{}';
ALTER TABLE items ADD COLUMN topics             TEXT[] NOT NULL DEFAULT '{}';
ALTER TABLE items ADD COLUMN tone               TEXT;
ALTER TABLE items ADD COLUMN reading_level      TEXT;
ALTER TABLE items ADD COLUMN enrichment_version INT;
