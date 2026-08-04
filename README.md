[🇩🇪 Deutsch](README.de.md) · 🇬🇧 English

# Summarizer Studio

![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F?logo=springboot&logoColor=white)
![Vaadin](https://img.shields.io/badge/Vaadin%20Flow-25-00B4F0?logo=vaadin&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17%20%2B%20pgvector-4169E1?logo=postgresql&logoColor=white)
![Ollama](https://img.shields.io/badge/LLM-Ollama%20local-000000?logo=ollama&logoColor=white)
[![Docker Hub](https://img.shields.io/docker/v/mtprause/summarizer?label=Docker%20Hub&logo=docker&logoColor=white&color=2496ED)](https://hub.docker.com/r/mtprause/summarizer)
[![Image Size](https://img.shields.io/docker/image-size/mtprause/summarizer/latest?logo=docker&logoColor=white&color=2496ED)](https://hub.docker.com/r/mtprause/summarizer)
![Privacy](https://img.shields.io/badge/Privacy-100%25%20local%20·%20no%20cloud-2e7d32)

Your personal knowledge archive - **runs fully local, no cloud, no API keys.**

Send in texts, links, PDFs, Office files, images or voice messages -
Summarizer reads them, summarizes them, sorts them into your categories
automatically, vectorizes them for semantic search and connects them into
a knowledge graph. Afterwards you can search in plain language.

## What the app does

| | |
|---|---|
| 🔎 **Semantic search** | Find by meaning instead of keyword - "recipe without cream" finds the carbonara entry |
| 💬 **Archive chat (RAG)** | Ask questions about your own content, answers come only from your archive, sources as clickable tiles |
| 🕸 **Knowledge graph** | People, technologies and topics are detected and connected automatically; filterable, colors per category, editable prompt |
| 🗂 **Auto categorization** | Your own categories with a description (= LLM instruction), hierarchical with drag & drop; bulk re-categorization |
| 📄 **Every format** | Web pages, PDF/Word/PowerPoint (Tika), images with vision description (llava), voice messages with Whisper transcription |
| 📱 **Capture from anywhere** | Telegram bot (QR pairing), Chrome extension, iOS shortcut - all secured by revocable API tokens |
| 🌐 **Bilingual** | German / English, switchable under System |
| 🔒 **Private** | LLM (Ollama), database and files stay on your machine; only the app port is exposed, all other ports bind to 127.0.0.1 |

More features: favorites and automatic tags, tag filter, ZIP backup
(all data + files), JSON export, multi-user with admin role, login optional
for purely local use, rate limiting and prompt-injection protection on the API.

## Architecture

```
Telegram bot     Chrome extension   Web UI (Vaadin)
     │                │ REST+Token      │ Session
     ▼                ▼                 ▼
┌─────────────────────────────────────────────┐
│   Spring Boot 4 + Vaadin Flow 25 (Java 21)  │
│   Ingest pipeline · RAG · Graph extraction  │
└──────┬───────────────┬──────────────┬───────┘
       ▼               ▼              ▼
 PostgreSQL 17    pgvector       Ollama (local)
 + Flyway         (HNSW)         Chat · Embedding · Vision
                                 + Whisper (audio)
```

Everything runs in Docker containers; the app ships as a ready-made image from
Docker Hub (**`mtprause/summarizer`**). Data lives in Docker volumes and survives
restarts and updates.

## Installation

Requirement: [Docker Desktop](https://www.docker.com/products/docker-desktop/)
(the installer sets it up if missing). Recommended: 8 GB RAM or more for the local LLM.

**Two files** from the
[releases page](https://github.com/martinprause/summarizer/releases/latest)
are enough to install - the installer creates the configuration (`.env`) and
the `docker-compose.yml` itself and pulls everything else from Docker Hub.
You only need the full repository for development.

### Windows

Easiest: download **[⬇ Summarizer-Setup.exe](https://github.com/martinprause/summarizer/releases/latest/download/Summarizer-Setup.exe)**
and double-click it - a graphical wizard with folder picker, language and LLM
selection and a progress view. It also creates the **"Summarizer"** desktop
shortcut (starts Docker, the containers and the browser with one click).

Alternatively via script:

1. Put **[⬇ install.bat](https://github.com/martinprause/summarizer/releases/latest/download/install.bat)**
   and **[⬇ install.ps1](https://github.com/martinprause/summarizer/releases/latest/download/install.ps1)**
   into an empty folder (e.g. `C:\Summarizer`)
2. Double-click **`install.bat`**
3. Answer the questions: content language, local LLM yes/no
4. The browser opens **http://localhost:8181** automatically

### Linux / macOS

Put **[⬇ install.sh](https://github.com/martinprause/summarizer/releases/latest/download/install.sh)**
into an empty folder, then:

```bash
chmod +x install.sh
./install.sh
```

The installer pulls the ready-made image from Docker Hub - nothing is built locally.

## First steps

1. **No login needed**: the studio opens directly by default (purely local use).
   Enable login: **System → Access → "Login required"** (takes effect after a restart).
   Credentials then: **`admin` / `admin`** - please change the default password right
   away under **Users**; the studio shows a warning while it is active
2. Create **categories** - the description is the sorting instruction for the LLM
3. **Connect Telegram**: System → Telegram bot → enter the token from @BotFather →
   "Connect with Telegram (QR)" → scan. From then on everything you send to the bot
   lands in your archive, sorted automatically
4. Install the **Chrome extension** - see [guide below](#installing-the-chrome-extension)

**Good to know:** if the machine is off, Telegram keeps sent messages in its queue
for about 24 hours - on the next start the app catches up automatically and reports
it in the studio.

## Installing the Chrome extension

Saves web pages, selected text and images with one click or via the context menu.

1. Download **[⬇ summarizer-chrome-extension.zip](https://github.com/martinprause/summarizer/releases/latest/download/summarizer-chrome-extension.zip)**
   and unzip it into a permanent folder (e.g. `C:\Summarizer\chrome-extension`) -
   Chrome loads the extension from there, so the folder must not be deleted
2. Open Chrome → address bar: `chrome://extensions`
3. Enable **Developer mode** (top right)
4. Click **"Load unpacked"** → select the unzipped folder
5. Get an API token in the studio: **API Tokens → New token** → copy it
   (shown only once)
6. Right-click the extension icon in Chrome → **Options**:
   - **Server URL**: `http://localhost:8181` (or your `APP_PORT`)
   - **Token**: the copied API token
   - Save - the connection test reports success

After that: clicking the icon saves the current page; right-click on a
page/selection/image → "Send to Summarizer". Green ✓ on the icon = saved,
red ! = error (e.g. server off - check the message and send again).

Other Chromium browsers (Edge, Brave, Vivaldi) work the same way
(`edge://extensions` etc.). Firefox is currently not supported.

## Operations

- **Start**: the "Start Summarizer" shortcut (created by the installer) - or start
  the **summarizer** container group in Docker Desktop
- **Stop**: stop the group in Docker Desktop - all data is kept
- **Update**: simply run the installer again (`install.bat` or `./install.sh`) -
  it pulls the latest image from Docker Hub and replaces only the app; database,
  files and models are kept. Migrations run automatically on startup

**Backup:** Studio → System → "Download backup as ZIP" - all content, categories,
tags, graph and chat as JSON plus original files and snapshots.

**Deleting data** (deliberate, irreversible): remove the group **including volumes**
in Docker Desktop - take a backup first.

## Configuration (`.env`)

| Variable | Meaning | Default |
|---|---|---|
| `APP_PORT` | Port of the web UI | `8181` |
| `APP_IMAGE` | Alternative app image | `mtprause/summarizer:latest` |
| `ADMIN_PASSWORD` | Admin password (empty = default `admin`, changeable in the studio) | - |
| `CHAT_MODEL` | Ollama chat model (downloaded on first start) | `qwen3.5:4b` |
| `EMBEDDING_MODEL` | Embedding model - English: `nomic-embed-text` (768), German/multilingual: `bge-m3` (1024) | `nomic-embed-text` |
| `EMBEDDING_DIM` | Must match the embedding model | `768` |
| `WHISPER_MODEL` | Whisper size (tiny/base/small/medium) | `small` |

Models can be switched later in the studio under **AI Models** - when the embedding
model changes, the app rebuilds the vector database automatically.

## Development

```bash
docker compose up -d postgres          # database only
mvn spring-boot:run                    # dev server on http://localhost:8080
```

Java 21, Spring Boot 4, Vaadin Flow 25, PostgreSQL 17 + pgvector, Flyway (V1-V9),
Ollama API, UI updates via Vaadin Push (virtual threads). Translations live in
`src/main/resources/i18n/`. Build the image: `docker build -t mtprause/summarizer:latest .`
