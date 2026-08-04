-- Link-Pruefung: NULL = ungeprueft, TRUE = erreichbar, FALSE = toter Link
ALTER TABLE items ADD COLUMN link_ok BOOLEAN;
