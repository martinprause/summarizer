# Summarizer — Persönliches Wissens-Studio

Konzept und Implementierungs-Outline für ein selbst-gehostetes (oder Cloud-)System zum Sammeln, automatischen Einsortieren und Durchsuchen von Texten, Bildern, Bookmarks und Webseiten.

---

## 1. Vision & Überblick

Ein zentrales "Studio" (Vaadin-Weboberfläche), in das Inhalte aus drei Quellen fließen:

1. **Telegram-Bot** — Texte, Links, Fotos, Sprachnachrichten, Dateien von unterwegs
2. **Chrome-Addon** — Webseiten mit einem Klick speichern
3. **Weboberfläche** — direkte Eingabe/Upload im Studio

Alle Inhalte werden:
- automatisch in **frei definierbare Kategorien des Users** einsortiert (LLM-gestützt)
- **vektorisiert** (Embeddings) für semantische Suche
- in einer **relationalen DB** mit Metadaten (Datum, Typ, Quelle, Kategorie) abgelegt

Betriebsmodi:
- **Komplett lokal**: Docker Compose + Ollama (kleines LLM + Embedding-Modell) + ngrok-Tunnel für Zugriff von außen
- **Cloud**: gleiches Docker-Setup auf beliebigem Host (Hetzner, Fly.io, etc.), LLM wahlweise per API

---

## 2. Architektur

```
┌─────────────┐   ┌──────────────┐   ┌─────────────────┐
│ TelegramBot │   │ Chrome Addon │   │ Web-UI (Vaadin) │
└──────┬──────┘   └──────┬───────┘   └────────┬────────┘
       │  REST + Token   │  REST + Token      │ Session
       ▼                 ▼                    ▼
┌─────────────────────────────────────────────────────┐
│              Vaadin / Spring Boot Backend           │
│  ┌───────────┐ ┌────────────┐ ┌──────────────────┐  │
│  │ Ingest-API│ │ Kategorisier│ │ Such-/Query-API │  │
│  │ (Upload)  │ │ -Pipeline   │ │ (Vektor+Filter) │  │
│  └───────────┘ └────────────┘ └──────────────────┘  │
└──────┬──────────────┬──────────────────┬────────────┘
       ▼              ▼                  ▼
┌────────────┐ ┌─────────────┐  ┌────────────────────┐
│ PostgreSQL │ │  pgvector    │  │ Ollama (lokal)     │
│ (Metadaten,│ │ (Embeddings, │  │ oder Cloud-LLM-API │
│  User,     │ │  in gleicher │  │ (Klassifikation +  │
│  Token)    │ │  Postgres!)  │  │  Embeddings)       │
└────────────┘ └─────────────┘  └────────────────────┘
       ▲
       │ Dateisystem-Volume: Bilder / Snapshots / Assets
       │
   ngrok-Tunnel  ◄──  Zugriff von unterwegs (App, Addon)
```

### Technologie-Entscheidungen

| Komponente | Wahl | Begründung |
|---|---|---|
| Backend | Spring Boot 4 + Vaadin Flow 25.2 (Java 21, Spring Framework 7) | Ein Deployment, UI + API in einem Artefakt; Vaadin 25 setzt Spring Boot 4 voraus |
| Relationale DB | PostgreSQL 16 | Kostenfrei, robust, Docker-ready |
| Vektor-DB | **pgvector** (Postgres-Extension) | Kostenfrei, keine zweite DB nötig, HNSW-Index, reicht für Millionen Einträge. Alternative: Qdrant (eigener Container), falls später mehr Vektor-Features nötig |
| LLM lokal | Ollama mit `llama3.2:3b` oder `qwen2.5:3b` (Klassifikation) | Passt in 4–8 GB RAM |
| Embeddings lokal | Sprachabhängig, wird bei Installation gewählt: `nomic-embed-text` (Englisch) oder `bge-m3` (Deutsch/multilingual) | Sprache wird einmalig im Installer eingestellt |
| LLM Cloud (optional) | Claude Haiku / beliebige API | Konfigurierbar per ENV |
| Mobil | Telegram-Bot (Long-Polling) | Kein Tunnel nötig, Warteschlange bei Server-Ausfall (~24 h) |
| Browser-Addon | Chrome Extension Manifest V3 | Ein-Klick-Speichern |
| Auth App/Addon | Personal Access Tokens (Bearer), gehasht in DB, pro User | Einfach, widerrufbar, pro Gerät |
| Auth Web-UI | Spring Security, umschaltbar per `AUTH_MODE`: **lokal** = Username/Passwort, **cloud** = Google OAuth2-Login | Mehrbenutzerfähig in beiden Modi |
| Container | Docker Compose | app + postgres + ollama + ngrok |
| Tunnel | ngrok (Authtoken des Users) | Zugriff von außen ohne Portfreigabe |

---

## 3. Datenmodell (relational)

```sql
users          (id, username, email, password_hash,   -- password_hash nur bei AUTH_MODE=local
                auth_provider,                          -- LOCAL | GOOGLE
                google_sub,                             -- Google Subject-ID bei OAuth
                role,                                   -- ADMIN | USER
                created_at)
api_tokens     (id, user_id, name, token_hash, last_used_at, created_at, revoked)
categories     (id, user_id, name, description, color, sort_order)
               -- description wird dem LLM als Klassifikations-Anweisung mitgegeben!
items          (id, user_id, type,            -- TEXT | IMAGE | BOOKMARK | WEBPAGE
                title, source_url, raw_text,   -- extrahierter Text
                file_path,                     -- Bild/Snapshot auf Volume
                category_id, category_confidence,
                created_at, captured_at)
item_embeddings(item_id, chunk_index, chunk_text, embedding vector(768))
tags           (id, user_id, name)             -- optional, Phase 2
item_tags      (item_id, tag_id)
```

Kernidee Kategorisierung: Der User pflegt Kategorien **mit Beschreibung** ("Rezepte — alles rund ums Kochen und Backen"). Die Pipeline schickt Titel + Textauszug + Kategorienliste an das LLM → LLM wählt Kategorie + Confidence. Bei niedriger Confidence: Kategorie "Unsortiert" im Studio zur manuellen Nachsortierung.

---

## 4. Ingest-Pipeline (asynchron)

1. **Empfang** — `POST /api/v1/items` (Token-Auth): Text, URL oder Multipart-Bild. Sofort `202 Accepted` + Item-ID, Verarbeitung im Hintergrund (Spring `@Async` / Job-Tabelle).
2. **Extraktion**
   - URL/Webseite: Abruf + Readability-Extraktion (z. B. `crux` / jsoup-basiert), Titel, Haupttext, optional Screenshot
   - Bild: Speichern auf Volume; Beschreibung/OCR per Vision-fähigem Modell (Ollama `llava` oder `moondream`) — Phase 2, initial nur EXIF/Dateiname
   - Text: direkt übernehmen, Titel per LLM generieren
3. **Kategorisierung** — LLM-Prompt mit User-Kategorien → `category_id` + Confidence
4. **Chunking + Embedding** — Text in ~500-Token-Chunks, Embedding pro Chunk → pgvector
5. **Fertig** — Item erscheint im Studio; App/Addon können Status pollen (`GET /api/v1/items/{id}`)

---

## 5. Komponenten im Detail

### 5.1 Vaadin Studio (Web-UI)

- **Dashboard**: neueste Items als Karten-Grid (Thumbnail, Titel, Kategorie-Badge, Datum)
- **Kategorie-Ansicht**: Seitennavigation nach Kategorien, Item-Zähler
- **Suche**:
  - Volltext + **Vektorsuche** (Suchtext wird embedded, Cosine-Similarity in pgvector)
  - Filter: Datum (von/bis), Typ, Kategorie — kombinierbar
- **Item-Detail**: Vorschau (Bild, Webseiten-Snapshot, Text), Original-Link, Kategorie ändern, löschen
- **Kategorien-Verwaltung**: CRUD mit Name, Beschreibung (= LLM-Anweisung), Farbe
- **Einstellungen**: API-Tokens erzeugen/widerrufen (Token nur einmal sichtbar), LLM-Konfiguration (Ollama vs. Cloud-API), ngrok-Status
- Prinzip: **einfach** — wenige Views, klare Navigation, Lumo/Aura-Theme

### 5.2 Telegram-Bot (mobiler Zugang)

- Einziger Weg von außen: Bot pollt Telegram (Long-Polling), kein Tunnel/Webhook nötig
- Pairing per QR-Deep-Link aus dem Studio (System → Telegram)
- Nimmt Texte, Links, Fotos, Sprachnachrichten und Dokumente entgegen
- Bei Server-Ausfall puffert Telegram Nachrichten ~24 h; beim Start wird der Rückstand
  im Hintergrund nachgeholt und per Push-Hinweis im Studio gemeldet
- (Die frühere Flutter-App wurde entfernt — Telegram ersetzt sie vollständig)

### 5.3 Chrome-Addon

- Manifest V3, Popup mit einem Button "Seite speichern"
- Sendet URL + Titel + (optional) markierten Text an `POST /api/v1/items`
- Options-Seite: Server-URL + Token
- Kontextmenü: "Auswahl an Summarizer senden", "Bild an Summarizer senden"
- Badge-Feedback: ✓ bei Erfolg, ! bei Fehler

### 5.4 Mehrbenutzer & Auth

Das System ist von Anfang an mehrbenutzerfähig — alle Tabellen tragen `user_id`, jede Query ist user-gescopet. Zwei Auth-Modi, per `AUTH_MODE` in `.env` umschaltbar:

| | Lokal (`AUTH_MODE=local`) | Cloud (`AUTH_MODE=google`) |
|---|---|---|
| Login Web-UI | Username + Passwort (Login-View) | "Sign in with Google" (OAuth2 / OIDC) |
| User-Anlage | Admin legt User im Studio an; erster Admin beim ersten Start (ENV/CLI) | Selbst-Registrierung per Google-Login; optional Allowlist erlaubter E-Mail-Adressen (`ALLOWED_EMAILS`) |
| Passwort-Reset | Durch Admin | Entfällt (Google) |
| App/Addon | Personal Access Token — identisch in beiden Modi | Personal Access Token — identisch in beiden Modi |

- Rollen: `ADMIN` (User-Verwaltung, Systemeinstellungen) und `USER`
- Kategorien, Items, Tokens strikt pro User getrennt — kein Zugriff auf fremde Inhalte
- Google-Login: Spring Security `oauth2Login()`, Client-ID/Secret aus `.env`; User-Matching über `google_sub`, bei Erstlogin automatische User-Anlage (falls Allowlist erfüllt)

### 5.5 Sicherheit

- Tokens: 32-Byte random, nur SHA-256-Hash in DB, Prefix zur Identifikation (`sum_xxxx…`)
- Rate-Limiting auf Ingest-API
- ngrok mit Basic-Auth oder OAuth zusätzlich absicherbar (ngrok-Feature)
- HTTPS durch ngrok automatisch; bei Cloud-Deployment: Reverse-Proxy (Caddy) mit Let's Encrypt
- CORS nur für Addon-Origin

---

## 6. Deployment

### 6.1 Docker Compose (`docker-compose.yml`)

```yaml
services:
  app:        # Spring Boot + Vaadin, Port 8080
  postgres:   # postgres:16 + pgvector, Volume pgdata
  ollama:     # ollama/ollama, Volume ollama-models, Modelle beim ersten Start gezogen
  ngrok:      # ngrok/ngrok, Tunnel auf app:8080, NGROK_AUTHTOKEN aus .env
```

Profile:
- `local-llm` (Standard): mit Ollama-Container
- `cloud-llm`: ohne Ollama, `LLM_PROVIDER=anthropic|openai` + API-Key in `.env`

### 6.2 Installations-Routine (Win/Mac/Linux)

Ein Installer-Skript pro Plattform (`install.ps1`, `install.sh`):

1. Prüfe ob Docker vorhanden → falls nicht: Download + Installation anstoßen (Docker Desktop bzw. `get.docker.com` unter Linux), User-Hinweis bei nötigem Neustart
2. Repository/Release-Paket herunterladen (compose-Datei + `.env.example`)
3. Interaktive Abfrage: **Sprache der Inhalte** (einmalig, bestimmt Embedding-Modell: `nomic-embed-text` für Englisch, `bge-m3` für Deutsch/multilingual), ngrok-Authtoken (optional), lokales LLM ja/nein, RAM-Erkennung → Modell-Empfehlung (3B bei <16 GB, 7–8B darüber)
4. `docker compose up -d` + Ollama-Modelle pullen
5. Health-Check + Ausgabe: lokale URL, ngrok-URL, initiales Admin-Passwort
6. Optional: Autostart (Task Scheduler / launchd / systemd)

### 6.3 Cloud-Variante

- Gleiches Compose auf VPS; ngrok entfällt, stattdessen Caddy + Domain
- Oder Managed: Fly.io / Railway (Postgres als Managed-DB mit pgvector)

---

## 7. Implementierungs-Schritte (Phasen)

### Phase 0 — Projekt-Setup (1–2 Tage)
- [x] Spring Boot 4 + Vaadin 25.2 Projekt-Skeleton, Java 21, Maven, Node.js 24 (Frontend-Build)
- [x] Docker Compose mit Postgres (pgvector-Image, Host-Port **5433** — 5432 belegt durch lokale PostgreSQL-15-Installation) + Flyway-Migrationen
- [x] Datenmodell (Tabellen aus Abschnitt 3) per Flyway `V1__init.sql`; JPA-Entities + Repositories folgen in Phase 1
- [x] Start aus VS Code: `.vscode/launch.json` („Summarizer (Spring Boot + Vaadin)") oder `mvn spring-boot:run`
- [ ] Spring Security: Login-View (lokal), User-Anlage per CLI/ENV beim ersten Start, `AUTH_MODE`-Umschaltung vorbereitet
- [ ] Mehrbenutzer-Grundlage: `user_id`-Scoping in allen Repositories, Rollen ADMIN/USER

### Phase 1 — Kern-Backend (1 Woche)
- [x] Ingest-API (`POST /api/v1/items`, JSON + Multipart-Upload) mit Token-Auth-Filter, dazu `GET /items`, `GET /items/{id}`, `GET /search`
- [x] Token-Verwaltung (Erzeugen, SHA-256-Hash, Widerrufen) + Tokens-View unter `/tokens`; Erststart legt Admin, Beispiel-Kategorien und Initial-Token an (wird einmalig geloggt)
- [x] Webseiten-Extraktion (jsoup, Readability light: article/main-Heuristik)
- [x] Ollama-Anbindung: eigener REST-Client statt Spring AI (Boot-4-Kompatibilität); Klassifikations-Prompt (`Name|Konfidenz`-Format) + Embeddings via `/api/embed`
- [x] Async-Pipeline (`@Async`) mit Status PENDING→PROCESSING→DONE/FAILED
- [x] pgvector-Speicherung (Chunking 1200 Zeichen / 150 Overlap) + Cosine-Similarity-Suche, Testsuche in MainView

### Phase 2 — Studio-UI (1 Woche)
- [x] Karten-Grid Dashboard mit Nachlade-Paging (24er-Seiten, "Mehr laden")
- [x] Kategorien-CRUD-View (Name, LLM-Beschreibung, Farbe, Reihenfolge)
- [x] Suche: Vektor (Checkbox "Semantisch") ODER Volltext (ILIKE), kombiniert mit Kategorie-/Typ-/Datumsfiltern; Fallback auf Volltext wenn Ollama offline
- [x] Item-Detail (`/item/{id}`): Text-/Bild-Vorschau, Quell-Link, Umkategorisierung (manuell = Konfidenz 100 %), Löschen inkl. Datei + Vektoren
- [x] "Unsortiert"-Inbox (`/inbox`): Kategorie fehlt oder Konfidenz < 50 %, Schnell-Zuordnung im Grid
- [x] Admin-View (`/users`): anlegen, sperren (Spalte `locked`, Migration V2), Rolle ändern — Durchsetzung folgt mit Login in Phase 4
- [x] App-Layout mit Drawer-Navigation (`@Layout`), System-Status-View (`/system`)

### Phase 3 — Clients (1 Woche)
- [x] Chrome-Addon (MV3) unter `clients/chrome-extension/`: Popup (Seite/Auswahl speichern), Options (URL+Token mit Verbindungstest), Kontextmenüs (Seite/Auswahl/Bild), Badge-Feedback — laden über `chrome://extensions` → Entwicklermodus → "Entpackte Erweiterung laden"
- [x] ~~Flutter-App~~ — entfernt, mobiler Zugang läuft komplett über den Telegram-Bot
- [x] QR-Code-Generierung im Studio (zxing → SVG): Token-Dialog zeigt Pairing-QR mit `{"url":"…","token":"…"}`; Server-URL berücksichtigt `X-Forwarded-*` (ngrok)

### Phase 4 — Packaging & Installer (3–5 Tage)
- [x] Login lokal (`AUTH_MODE=local`): Spring Security + Vaadin LoginForm, BCrypt, Admin-Passwort aus ENV oder generiert (einmalig geloggt), Sperren wirksam, `/users` nur für ADMIN, Logout im Header
- [x] Google-OAuth2-Login (`AUTH_MODE=google`) inkl. E-Mail-Allowlist und Auto-Provisionierung (Code fertig, Live-Test erfordert Google-Client-ID/Secret)
- [x] Multi-Stage-Dockerfile für App (Vaadin Production-Build)
- [x] Compose-Profile: `app`, `local-llm` (Ollama-Container), `ngrok` (Tunnel, statische Domain via `NGROK_DOMAIN`); ohne Profil nur Postgres für lokale Entwicklung
- [x] `install.ps1` / `install.sh`: Docker-Bootstrap (winget / get.docker.com), Sprachwahl → Embedding-Modell (inkl. automatischer Dimension-Anpassung der Vektorspalte beim Start), RAM-Erkennung → Modell-Empfehlung, ngrok-Abfrage, `.env`-Generierung, Modell-Pull
- [ ] GitHub-Release-Pipeline (Image-Build, Installer-Artefakte) — offen, sobald Repo auf GitHub liegt
- [ ] ngrok-Status-Anzeige im Studio (aktuell: ngrok-Inspector auf Port 4040)

### Phase 5 — Ausbau (offen)
- [x] Bild-Verständnis: Beschreibung + OCR via Vision-Modell (`VISION_MODEL`, Default llava:7b) — Bilder werden dadurch klassifizier- und durchsuchbar
- [x] Auto-Zusammenfassung: 2-3 Sätze pro Item (Pipeline-Schritt), auf Karten und in der Detail-Ansicht, fließt in die Embeddings ein
- [x] Webseiten-Snapshot: Roh-HTML als Offline-Kopie (`/files/{id}/snapshot`, Download)
- [x] Tags zusätzlich zu Kategorien (frei, Komma-getrennt in der Detail-Ansicht, Chips auf Karten)
- [x] Export als JSON (System-View), Backup weiterhin via pg_dump
- [x] Chat: Token-Streaming (@Push, virtueller Thread), Markdown-Rendering (commonmark, escaped), persistenter Verlauf (V7)
- [x] Pipeline-Robustheit: Fehlergrund am Item + Retry-Button, begrenzter Thread-Pool, URL-Dedup beim Ingest
- [x] Suche: pg_trgm-Indizes auf Titel/Text (beschleunigt ILIKE), Re-Embedding-Button unter KI-Modelle
- [x] Graph: dagre-Layout (LR), Knoten-Klick → verknüpfte Inhalte, case-insensitive Entitäten-Dedup
- [x] Dashboard-UX: og:image-Thumbnails, Favicons, Infinite Scroll, Onboarding-Leerzustand, Dark-Mode-Toggle
- [x] Sicherheit: `/files/**` prüft jetzt den Besitzer (vorher konnten fremde IDs geladen werden)
- [ ] Webseiten-Screenshots gerendert (Playwright-Container) — Offline-HTML ersetzt das vorerst
- [x] RAG-Chat: "Frage dein Archiv" im Studio (`/chat`): pgvector-Retrieval Top-6 → LLM-Antwort mit Quellen-Links (vorgezogen)
- [x] GraphRAG (vorgezogen): LLM extrahiert Entitäten + Beziehungen je Item (Migration V5), Wissensgraph-View `/graph` mit React-Flow-Visualisierung (ReactAdapterComponent-Muster), Chat nutzt Graph-Fakten als Zusatzkontext, Backfill-Button für Bestandsdaten
- [x] LLM-Provider wählbar (vorgezogen): Studio `/models` (ADMIN) — Ollama-Modelle auflisten/herunterladen/löschen (kuratierte Liste, Default qwen3.5:4b) oder eigener OpenAI-/Anthropic-API-Key (Migration V6, `LlmRouter`); Embeddings bleiben lokal via Ollama
- [x] Kategorien-Hierarchie (vorgezogen): `parent_id` (Migration V3), Eltern-Auswahl im Editor mit Zyklus-Schutz, ein-/ausblendbare TreeGrid-Sidebar im Dashboard, Filter wirkt inkl. Unterkategorien

---

## 8. Getroffene Entscheidungen

| Frage | Entscheidung |
|---|---|
| Vektor-DB | **pgvector** (eine DB weniger) |
| Embedding-Modell | **Sprachabhängig** — Sprache wird einmalig bei der Installation eingestellt; danach fest (Modellwechsel erfordert Re-Embedding aller Inhalte) |
| Bild-Pipeline | **Später** (Phase 5) — initial nur Ablage auf Volume |
| iOS-Support App | **Ja** — Share-Extension in Phase 3 mit eingeplant |
| Vaadin Flow vs. Hilla | **Flow** (reines Java, schnelleres MVP) |
| Mehrbenutzer | **Ja, von Anfang an** — lokal wie cloud. Auth: Google-Login (cloud) bzw. Username/Passwort (lokal), umschaltbar per `AUTH_MODE` |
