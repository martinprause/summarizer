-- Favoriten-Markierung, System-Kategorien, Dashboard-Einstellungen pro User

ALTER TABLE items ADD COLUMN favorite BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE categories ADD COLUMN system_type VARCHAR(20);   -- FAVORITES | NULL
ALTER TABLE users ADD COLUMN dashboard_sort VARCHAR(100) NOT NULL DEFAULT 'DATE,CATEGORY,TYPE';
ALTER TABLE users ADD COLUMN dashboard_view VARCHAR(10) NOT NULL DEFAULT 'TILES';

-- Standard-Kategorie "Favoriten" fuer alle bestehenden User
INSERT INTO categories (user_id, name, description, color, sort_order, system_type)
SELECT id, 'Favoriten', 'Manuell markierte Favoriten', '#f9a825', -1, 'FAVORITES'
FROM users
ON CONFLICT (user_id, name) DO NOTHING;

CREATE INDEX idx_items_user_favorite ON items (user_id) WHERE favorite;
