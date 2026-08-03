[🇩🇪 Deutsch](INSTALL.de.md) · 🇬🇧 English

# Installing Summarizer

Everything runs locally in Docker - the app ships as a **ready-made image from
Docker Hub** (`mtprause/summarizer`), nothing is built on your machine.
Access from anywhere works through the Telegram bot - no tunnel, no port
forwarding, no cloud needed.

The installer files are all you need - they create `docker-compose.yml` and
`.env` themselves. The repository is only needed for the Chrome extension
or the source code.

## Windows

1. Put **`install.bat`** + **`install.ps1`** into an empty folder (e.g. `C:\Summarizer`)
2. Double-click **`install.bat`**
3. Answer the questions (content language, local LLM)
4. The browser opens **http://localhost:8181** automatically

If **WSL 2** is missing (required by Docker Desktop), the script installs it
first (admin prompt, then restart Windows and run the script again). If Docker
Desktop is missing, the script installs it via `winget` - then start Docker
once and run `install.bat` again.

## Linux / macOS

Put **`install.sh`** into an empty folder, then:

```bash
chmod +x install.sh
./install.sh
```

If Docker is missing, the script installs it automatically on Linux
(`get.docker.com`); on macOS it points you to Docker Desktop.

## What the installer does

1. Check / install Docker, start the engine
2. Questions: content language (determines the embedding model), local LLM yes/no -
   RAM detection picks a fitting chat model; admin credentials are fixed at `admin`/`admin`
3. Find a free port (default **8181**, otherwise the next free one)
4. Write `.env`, pull the ready-made images from Docker Hub and start the
   containers - **nothing is built locally**
5. Models download in the background (`ollama-init`)
6. Wait until the app responds → open the browser

## Afterwards

| | |
|---|---|
| Studio | http://localhost:8181 |
| Login | **OFF** by default - the studio opens directly |
| Enable login | System → Access → "Login required" (takes effect after a restart) |
| Credentials with login | `admin` / `admin` - **change the default password under "Users"!** |
| On the go | Studio → System → Telegram bot → enter token → scan QR |

## Operations

- **Start**: "Start Summarizer" shortcut or Docker Desktop → group **summarizer**
- **Stop**: Docker Desktop → stop the group - data is kept
- **Update**: run the installer again - pulls the latest image and replaces only
  the app; database, files and models are kept, migrations run automatically
- **Backup**: Studio → System → "Download backup as ZIP"

Data lives in Docker volumes and survives restarts, updates and stopping.
It is only deleted if you remove the group **including volumes** in Docker Desktop.

## Advanced (command line)

```bash
docker compose --profile app --profile local-llm ps         # status
docker compose --profile app --profile local-llm logs -f    # logs
docker compose --profile whisper up -d                      # audio transcription
docker exec summarizer-postgres pg_dump -U summarizer summarizer > backup.sql
```
