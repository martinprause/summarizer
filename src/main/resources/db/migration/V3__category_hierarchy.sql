-- Kategorien koennen hierarchisch verschachtelt werden
ALTER TABLE categories ADD COLUMN parent_id BIGINT REFERENCES categories (id) ON DELETE SET NULL;
