-- Graph-Extraktion: neuer, strengerer Standard-Prompt (weniger, dafuer echte
-- Entitaeten). Gespeicherten Alt-Prompt entfernen, damit der neue Default greift.
DELETE FROM app_settings WHERE key = 'graph.prompt';
