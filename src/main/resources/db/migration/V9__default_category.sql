-- "Privat" ist die geschuetzte Standard-Kategorie (Auffangbecken)
UPDATE categories SET system_type = 'DEFAULT'
WHERE lower(name) = 'privat' AND system_type IS NULL;

-- Fehlt sie bei einem User, anlegen
INSERT INTO categories (user_id, name, description, color, sort_order, system_type)
SELECT u.id, 'Privat', 'Persoenliches: alles ohne passendere Kategorie', '#7b1fa2', 100, 'DEFAULT'
FROM users u
WHERE NOT EXISTS (
    SELECT 1 FROM categories c WHERE c.user_id = u.id AND lower(c.name) = 'privat'
);

-- Namen case-insensitive eindeutig pro User
-- (Constraint zuerst loesen — er haelt den zugehoerigen Index fest)
ALTER TABLE categories DROP CONSTRAINT IF EXISTS categories_user_id_name_key;
DROP INDEX IF EXISTS categories_user_id_name_key;
CREATE UNIQUE INDEX IF NOT EXISTS uq_categories_user_lower_name
    ON categories (user_id, lower(name));
