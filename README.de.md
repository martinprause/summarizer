🇩🇪 Deutsch · [🇬🇧 English](README.md)

# Summarizer Studio

![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F?logo=springboot&logoColor=white)
![Vaadin](https://img.shields.io/badge/Vaadin%20Flow-25-00B4F0?logo=vaadin&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17%20%2B%20pgvector-4169E1?logo=postgresql&logoColor=white)
![Ollama](https://img.shields.io/badge/LLM-Ollama%20lokal-000000?logo=ollama&logoColor=white)
[![Docker Hub](https://img.shields.io/docker/v/mtprause/summarizer?label=Docker%20Hub&logo=docker&logoColor=white&color=2496ED)](https://hub.docker.com/r/mtprause/summarizer)
[![Image Size](https://img.shields.io/docker/image-size/mtprause/summarizer/latest?logo=docker&logoColor=white&color=2496ED)](https://hub.docker.com/r/mtprause/summarizer)
![Privacy](https://img.shields.io/badge/Privacy-100%25%20lokal%20·%20keine%20Cloud-2e7d32)

Dein persönliches Wissensarchiv - **läuft komplett lokal, keine Cloud, keine API-Keys.**

Schick Texte, Links, PDFs, Office-Dateien, Bilder oder Sprachnachrichten hinein -
Summarizer liest sie, fasst sie zusammen, sortiert sie automatisch in deine Kategorien ein,
vektorisiert sie für semantische Suche und verknüpft sie zu einem Wissensgraphen.
Suchen kannst du danach in normaler Sprache.

## Was die App kann

| | |
|---|---|
| 🔎 **Semantische Suche** | Finden nach Bedeutung statt Stichwort - „Rezept ohne Sahne" findet den Carbonara-Eintrag |
| 💬 **Archiv-Chat (RAG)** | Fragen an die eigenen Inhalte, Antworten nur aus dem Archiv, Quellen als klickbare Kacheln |
| 🕸 **Wissensgraph** | Personen, Technologien, Themen werden automatisch erkannt und verbunden; filterbar, Farben pro Kategorie, Prompt editierbar |
| 🗂 **Auto-Kategorisierung** | Eigene Kategorien mit Beschreibung (= LLM-Anweisung), hierarchisch mit Drag & Drop; Massen-Umkategorisierung |
| 📄 **Jedes Format** | Webseiten, PDF/Word/PowerPoint (Tika), Bilder mit Vision-Beschreibung (llava), Sprachnachrichten mit Whisper-Transkription |
| 📱 **Von überall befüllen** | Telegram-Bot (QR-Pairing), Chrome-Addon, iOS-Kurzbefehl - alles über widerrufbare API-Tokens |
| 🌐 **Zweisprachig** | Deutsch / English, umschaltbar unter System |
| 🔒 **Privat** | LLM (Ollama), Datenbank und Dateien bleiben auf deinem Rechner; nur der App-Port ist erreichbar, alle anderen Ports nur auf 127.0.0.1 |

Weitere Funktionen: Favoriten & automatische Tags, Tag-Filter, ZIP-Backup
(alle Daten + Dateien), JSON-Export, Mehrbenutzer mit Admin-Rolle, Login abschaltbar
für rein lokalen Betrieb, Rate-Limiting und Prompt-Injection-Schutz auf der API.

## Architektur

```
Telegram-Bot     Chrome-Addon     Web-UI (Vaadin)
     │                │ REST+Token      │ Session
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

Alles läuft in Docker-Containern; die App kommt als fertiges Image von Docker Hub
(**`mtprause/summarizer`**). Daten liegen in Docker-Volumes und überleben Neustarts
und Updates.

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

Der Installer lädt das fertige Image von Docker Hub - es wird nichts lokal gebaut.

## Erste Schritte

1. **Kein Login nötig**: Das Studio startet standardmäßig ohne Anmeldung (rein lokaler
   Betrieb). Login aktivieren: **System → Zugriff → „Login erforderlich"** (wirkt nach
   Neustart). Zugang dann: **`admin` / `admin`** - Standard-Passwort bitte gleich unter
   **Benutzer** ändern; das Studio warnt, solange es aktiv ist
2. **Kategorien** anlegen - die Beschreibung ist die Einsortier-Anweisung für das LLM
3. **Telegram verbinden**: System → Telegram-Bot → Token von @BotFather eintragen → „Mit Telegram verbinden (QR)" → scannen. Ab dann: alles, was du dem Bot schickst, landet automatisch einsortiert im Archiv
4. **Chrome-Addon** installieren - siehe [Anleitung unten](#chrome-addon-installieren)

**Gut zu wissen:** Ist der Rechner aus, hält Telegram gesendete Nachrichten ~24 Stunden
in der Warteschlange - beim nächsten Start holt die App alles automatisch nach und
meldet es im Studio.

## Chrome-Addon installieren

Speichert Webseiten, markierten Text und Bilder mit einem Klick oder per Rechtsklick-Menü.

1. Repository liegt lokal vor (per `git clone` oder ZIP-Download → entpacken).
   Wichtig: Der Ordner `clients/chrome-extension` muss dauerhaft liegen bleiben -
   Chrome lädt die Erweiterung von dort.
2. Chrome öffnen → Adresszeile: `chrome://extensions`
3. Oben rechts **„Entwicklermodus"** einschalten
4. **„Entpackte Erweiterung laden"** klicken → Ordner `clients/chrome-extension` auswählen
5. Im Studio ein API-Token holen: **API-Tokens → Neues Token** → Token kopieren
   (wird nur einmal angezeigt)
6. Extension-Symbol in Chrome → Rechtsklick → **„Optionen"**:
   - **Server-URL**: `http://localhost:8181` (bzw. dein `APP_PORT`)
   - **Token**: das kopierte API-Token
   - Speichern - der Verbindungstest meldet Erfolg

Danach: Extension-Symbol anklicken speichert die aktuelle Seite; Rechtsklick auf
Seite/Textauswahl/Bild → „… an Summarizer senden". Grünes ✓ am Symbol = gespeichert,
rotes ! = Fehler (z. B. Server aus - dann Meldung prüfen und erneut senden).

Andere Chromium-Browser (Edge, Brave, Vivaldi) funktionieren genauso
(`edge://extensions` usw.). Firefox wird derzeit nicht unterstützt.

## Betrieb

- **Starten**: Verknüpfung „Summarizer starten" (legt der Installer an) - oder in
  Docker Desktop die Container-Gruppe **summarizer** starten
- **Stoppen**: in Docker Desktop die Gruppe stoppen - alle Daten bleiben erhalten
- **Update**: Installer einfach erneut ausführen (`install.bat` bzw. `./install.sh`) -
  lädt das neueste Image von Docker Hub und ersetzt nur die App; Datenbank, Dateien
  und Modelle bleiben. Migrationen laufen beim Start automatisch

**Backup:** Studio → System → „Backup als ZIP herunterladen" - alle Inhalte, Kategorien,
Tags, Graph und Chat als JSON plus Original-Dateien und Snapshots.

**Daten löschen** (bewusst, unwiderruflich): in Docker Desktop die Gruppe samt
**Volumes** entfernen - vorher Backup ziehen.

## Konfiguration (`.env`)

| Variable | Bedeutung | Standard |
|---|---|---|
| `APP_PORT` | Port der Weboberfläche | `8181` |
| `APP_IMAGE` | Alternatives App-Image | `mtprause/summarizer:latest` |
| `ADMIN_PASSWORD` | Admin-Passwort (leer = Standard `admin`, im Studio änderbar) | - |
| `CHAT_MODEL` | Ollama-Chat-Modell (Erststart-Download) | `qwen3.5:4b` |
| `EMBEDDING_MODEL` | Embedding-Modell - Englisch: `nomic-embed-text` (768), Deutsch/multilingual: `bge-m3` (1024) | `nomic-embed-text` |
| `EMBEDDING_DIM` | Muss zum Embedding-Modell passen | `768` |
| `WHISPER_MODEL` | Whisper-Größe (tiny/base/small/medium) | `small` |

Modelle lassen sich später im Studio unter **KI-Modelle** wechseln - bei einem
Embedding-Wechsel baut die App die Vektor-Datenbank automatisch neu auf.

## Entwicklung

```bash
docker compose up -d postgres          # nur DB
mvn spring-boot:run                    # Dev-Server auf http://localhost:8080
```

Java 21, Spring Boot 4, Vaadin Flow 25, PostgreSQL 17 + pgvector, Flyway (V1–V9),
Ollama-API, UI-Updates per Vaadin Push (virtuelle Threads). Übersetzungen unter
`src/main/resources/i18n/`. Image bauen: `docker build -t mtprause/summarizer:latest .`
