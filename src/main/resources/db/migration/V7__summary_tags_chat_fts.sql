-- Auto-Zusammenfassung, Fehlergrund, Thumbnails, Snapshots, Tags, Chat-Verlauf,
-- Trigram-Indizes fuer schnelle Volltextsuche, case-insensitive Entitaeten

ALTER TABLE items ADD COLUMN summary TEXT;
ALTER TABLE items ADD COLUMN error_message TEXT;
ALTER TABLE items ADD COLUMN thumbnail_url TEXT;
ALTER TABLE items ADD COLUMN snapshot_path TEXT;

CREATE TABLE tags (
    id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name    VARCHAR(60) NOT NULL,
    UNIQUE (user_id, name)
);

CREATE TABLE item_tags (
    item_id BIGINT NOT NULL REFERENCES items (id) ON DELETE CASCADE,
    tag_id  BIGINT NOT NULL REFERENCES tags (id) ON DELETE CASCADE,
    PRIMARY KEY (item_id, tag_id)
);

CREATE TABLE chat_messages (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role       VARCHAR(10) NOT NULL,   -- USER | ASSISTANT
    text       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_chat_user_created ON chat_messages (user_id, created_at);

-- Trigram-Indizes beschleunigen ILIKE '%...%' massiv
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_items_title_trgm ON items USING gin (title gin_trgm_ops);
CREATE INDEX idx_items_rawtext_trgm ON items USING gin (raw_text gin_trgm_ops);

-- Entitaeten case-insensitive eindeutig ("PostgreSQL" == "postgresql")
ALTER TABLE entities DROP CONSTRAINT entities_user_id_name_key;
CREATE UNIQUE INDEX uq_entities_user_lower_name ON entities (user_id, lower(name));
