-- GraphRAG: Entitaeten + Beziehungen, vom LLM aus Inhalten extrahiert

CREATE TABLE entities (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name        VARCHAR(200) NOT NULL,
    type        VARCHAR(30)  NOT NULL DEFAULT 'CONCEPT',  -- PERSON | ORG | TECH | ORT | CONCEPT
    description TEXT,
    UNIQUE (user_id, name)
);

CREATE TABLE item_entities (
    item_id   BIGINT NOT NULL REFERENCES items (id) ON DELETE CASCADE,
    entity_id BIGINT NOT NULL REFERENCES entities (id) ON DELETE CASCADE,
    PRIMARY KEY (item_id, entity_id)
);

CREATE TABLE entity_relations (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id   BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    source_id BIGINT NOT NULL REFERENCES entities (id) ON DELETE CASCADE,
    target_id BIGINT NOT NULL REFERENCES entities (id) ON DELETE CASCADE,
    relation  VARCHAR(300),
    item_id   BIGINT REFERENCES items (id) ON DELETE SET NULL
);

CREATE INDEX idx_entities_user ON entities (user_id);
CREATE INDEX idx_item_entities_entity ON item_entities (entity_id);
CREATE INDEX idx_relations_user ON entity_relations (user_id);
