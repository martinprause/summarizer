-- Phase 0: Grundschema Summarizer (siehe KONZEPT.md Abschnitt 3)

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE users (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username      VARCHAR(100) NOT NULL UNIQUE,
    email         VARCHAR(255) UNIQUE,
    password_hash VARCHAR(255),                 -- nur bei AUTH_MODE=local
    auth_provider VARCHAR(20)  NOT NULL DEFAULT 'LOCAL',  -- LOCAL | GOOGLE
    google_sub    VARCHAR(255) UNIQUE,          -- Google Subject-ID bei OAuth
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER',   -- ADMIN | USER
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE api_tokens (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name         VARCHAR(100) NOT NULL,
    token_hash   VARCHAR(64)  NOT NULL UNIQUE,  -- SHA-256 hex
    last_used_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    revoked      BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE TABLE categories (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name        VARCHAR(100) NOT NULL,
    description TEXT,                           -- Klassifikations-Anweisung fuer das LLM
    color       VARCHAR(20),
    sort_order  INT          NOT NULL DEFAULT 0,
    UNIQUE (user_id, name)
);

CREATE TABLE items (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id             BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type                VARCHAR(20) NOT NULL,   -- TEXT | IMAGE | BOOKMARK | WEBPAGE
    title               VARCHAR(500),
    source_url          TEXT,
    raw_text            TEXT,
    file_path           TEXT,
    category_id         BIGINT REFERENCES categories (id) ON DELETE SET NULL,
    category_confidence REAL,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING | PROCESSING | DONE | FAILED
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    captured_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_items_user_created ON items (user_id, created_at DESC);
CREATE INDEX idx_items_user_category ON items (user_id, category_id);

-- Embedding-Dimension haengt vom Modell ab (nomic-embed-text: 768, bge-m3: 1024).
-- Phase 0: 768 als Default; wird bei Installation ggf. per Migration angepasst.
CREATE TABLE item_embeddings (
    item_id     BIGINT NOT NULL REFERENCES items (id) ON DELETE CASCADE,
    chunk_index INT    NOT NULL,
    chunk_text  TEXT   NOT NULL,
    embedding   vector(768),
    PRIMARY KEY (item_id, chunk_index)
);

CREATE INDEX idx_item_embeddings_hnsw ON item_embeddings
    USING hnsw (embedding vector_cosine_ops);
