-- Phase 2: User sperrbar (Admin-Verwaltung)
ALTER TABLE users ADD COLUMN locked BOOLEAN NOT NULL DEFAULT FALSE;
