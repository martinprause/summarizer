# Summarizer Studio

Dein persönliches Wissensarchiv — **läuft komplett lokal, keine Cloud, keine API-Keys.**

Schick Texte, Links, PDFs, Office-Dateien, Bilder oder Sprachnachrichten hinein —
Summarizer liest sie, fasst sie zusammen, sortiert sie automatisch in deine Kategorien ein,
vektorisiert sie für semantische Suche und verknüpft sie zu einem Wissensgraphen.
Suchen kannst du danach in normaler Sprache.

## Was die App kann

| | |
|---|---|
| 🔎 **Semantische Suche** | Finden nach Bedeutung statt Stichwort — „Rezept ohne Sahne" findet den Carbonara-Eintrag |
| 💬 **Archiv-Chat (RAG)** | Fragen an die eigenen Inhalte, Antworten nur aus dem Archiv, Quellen als klickbare Kacheln |
| 🕸 **Wissensgraph** | Personen, Technologien, Themen werden automatisch erkannt und verbunden; filterbar, Farben pro Kategorie, Prompt editierbar |
| 🗂 **Auto-Kategorisierung** | Eigene Kategorien mit Beschreibung (= LLM-Anweisung), hierarchisch mit Drag & Drop; Massen-Umkategorisierung |
| 📄 **Jedes Format** | Webseiten, PDF/Word/PowerPoint (Tika), Bilder mit Vision-Beschreibung (llava), Sprachnachrichten mit Whisper-Transkription |
| 📱 **Von überall befüllen** | Telegram-Bot (QR-Pairing), Chrome-Addon, iOS-Kurzbefehl — alles über widerrufbare API-Tokens |
| 🌐 **Zweisprachig** | Deutsch / English, umschaltbar unter System |
| 🔒 **Privat** | LLM (Ollama), Datenbank und Dateien bleiben auf deinem Rechner; nur der App-Port ist erreichbar, alle anderen Ports nur auf 127.0.0.1 |

Weitere Funktionen: Favoriten & automatische Tags, Tag-Filter, ZIP-Backup
(alle Daten + Dateien), JSON-Export, Mehrbenutzer mit Admin-Rolle, Login abschaltbar
für rein lokalen Betrieb, Rate-Limiting und Prompt-Injection-Schutz auf der API.

## Architektur

```
Telegram-Bot     Chrome-Addon     Web-UI (Vaadin)
     │ Long-Poll      │ REST+Token      │ Session
     ▼                ▼                 ▼
┌─────────────────────────────────────────────┐
│   Spring Boot 4 + Vaadin Flow 25 (Java 21)  │
│   Ingest-Pipeline · RAG · Graph-Extraktion  │
└──────┬───────────────┬──────────────┬───────┘
       ▼               ▼              ▼
 PostgreSQL 17    pgvector       Ollama (lokal)
 + Flyway         (HNSW)         Chat · Embedding · Vision
                                 + Whisper (Audio)
```

Alles in Docker Compose; Daten in benannten Volumes (`pgdata`, `ollama`, `files`, `whisper`) —
sie überleben Neustarts, Updates und `docker compose down`. Erst `down -v` löscht sie.

## Installation

Voraussetzung: [Docker Desktop](https://www.docker.com/products/docker-desktop/)
(wird vom Installer bei Bedarf mitinstalliert). Empfohlen: 8 GB RAM oder mehr für das lokale LLM.

### Windows

1. Repository laden (`git clone` oder ZIP entpacken)
2. Doppelklick auf **`install.bat`**
3. Fragen beantworten: Sprache der Inhalte, lokales LLM ja/nein, Admin-Passwort
4. Browser öffnet automatisch **http://localhost:8181**

### Linux / macOS

```bash
git clone https://github.com/martinprause/summarizer.git
cd summarizer
chmod +x install.sh
./install.sh
```

### Manuell (ohne Installer)

```bash
cp .env.example .env      # anpassen: Passwörter, Modelle, Ports
docker compose --profile app --profile local-llm up -d --build
```

Audio-Transkription zusätzlich: `docker compose --profile whisper up -d`

### Fertiges Image statt Selbstbauen

In `.env` setzen, dann startet Compose ohne Build:

```
APP_IMAGE=<dockerhub-name>/summarizer:latest
```

## Erste Schritte

1. **Login**: `admin` + Passwort aus `.env` (leer gelassen → generiert, steht in `docker logs summarizer-app`)
2. **Kategorien** anlegen — die Beschreibung ist die Einsortier-Anweisung für das LLM
3. **Telegram verbinden**: System → Telegram-Bot → Token von @BotFather eintragen → „Mit Telegram verbinden (QR)" → scannen. Ab dann: alles, was du dem Bot schickst, landet automatisch einsortiert im Archiv
4. **Chrome-Addon**: `chrome://extensions` → Entwicklermodus → „Entpackte Erweiterung laden" → Ordner `clients/chrome-extension`; in den Optionen Server-URL + API-Token (Studio → API-Tokens) eintragen

**Gut zu wissen:** Ist der Rechner aus, hält Telegram gesendete Nachrichten ~24 Stunden
in der Warteschlange — beim nächsten Start holt die App alles automatisch nach und
meldet es im Studio.

## Betrieb

```bash
docker compose --profile app --profile local-llm ps        # Status
docker compose --profile app --profile local-llm logs -f   # Logs
docker compose --profile app --profile local-llm down      # stoppen (Daten bleiben)
docker compose --profile app --profile local-llm up -d     # starten
```

**Backup:** Studio → System → „Backup als ZIP herunterladen" (alle Inhalte, Kategorien,
Tags, Graph, Chat als JSON plus Original-Dateien). Zusätzlich klassisch:

```bash
docker exec summarizer-postgres pg_dump -U summarizer summarizer > backup.sql
```

**Update:**

```bash
git pull
docker compose --profile app --profile local-llm up -d --build
```

Datenbank-Migrationen (Flyway) laufen beim Start automatisch; Daten bleiben erhalten.

## Konfiguration (`.env`)

| Variable | Bedeutung | Standard |
|---|---|---|
| `APP_PORT` | Port der Weboberfläche | `8181` |
| `APP_IMAGE` | Fertiges Image statt lokalem Build | — |
| `ADMIN_PASSWORD` | Admin-Passwort (leer = generiert + geloggt) | — |
| `CHAT_MODEL` | Ollama-Chat-Modell (Erststart-Download) | `qwen3.5:4b` |
| `EMBEDDING_MODEL` | Embedding-Modell — Englisch: `nomic-embed-text` (768), Deutsch/multilingual: `bge-m3` (1024) | `nomic-embed-text` |
| `EMBEDDING_DIM` | Muss zum Embedding-Modell passen | `768` |
| `WHISPER_MODEL` | Whisper-Größe (tiny/base/small/medium) | `small` |

Modelle lassen sich später im Studio unter **KI-Modelle** wechseln — bei einem
Embedding-Wechsel baut die App die Vektor-Datenbank automatisch neu auf.

## Entwicklung

```bash
docker compose up -d postgres          # nur DB
mvn spring-boot:run                    # Dev-Server auf http://localhost:8080
```

Java 21, Spring Boot 4, Vaadin Flow 25, PostgreSQL 17 + pgvector, Flyway (V1–V9),
Ollama-API, UI-Push statt Polling (virtuelle Threads). Übersetzungen unter
`src/main/resources/i18n/`.
