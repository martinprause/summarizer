🇩🇪 Deutsch · [🇬🇧 English](INSTALL.md)

# Summarizer installieren

Alles läuft lokal in Docker - die App kommt als **fertiges Image von Docker Hub**
(`mtprause/summarizer`), es wird nichts auf deinem Rechner gebaut.
Zugriff von unterwegs geht über den Telegram-Bot - kein Tunnel, keine Portfreigabe,
keine Cloud nötig.

Zum Installieren reichen die Installer-Dateien - `docker-compose.yml` und `.env`
erzeugen sie selbst. Das Repository braucht nur, wer Chrome-Addon oder Quellcode will.

## Windows

1. **`install.bat`** + **`install.ps1`** in einen leeren Ordner legen (z. B. `C:\Summarizer`)
2. Doppelklick auf **`install.bat`**
3. Fragen beantworten (Sprache der Inhalte, lokales LLM)
4. Browser öffnet automatisch **http://localhost:8181**

Fehlt **WSL 2** (Voraussetzung für Docker Desktop), installiert das Skript es
zuerst (Adminrechte-Abfrage, danach Windows neu starten und Skript erneut
ausführen). Fehlt Docker Desktop, installiert das Skript es per `winget` -
danach Docker einmal starten und `install.bat` erneut ausführen.

## Linux / macOS

**`install.sh`** in einen leeren Ordner legen, dann:

```bash
chmod +x install.sh
./install.sh
```

Fehlt Docker, installiert das Skript es unter Linux automatisch
(`get.docker.com`); unter macOS kommt ein Hinweis auf Docker Desktop.

## Was der Installer macht

1. Docker prüfen / installieren, Engine starten
2. Fragen: Sprache der Inhalte (bestimmt das Embedding-Modell), lokales LLM ja/nein -
   RAM-Erkennung wählt das passende Chat-Modell; Admin-Zugang ist fest `admin`/`admin`
3. Freien Port suchen (Standard **8181**, sonst nächster freier)
4. `.env` schreiben, fertige Images von Docker Hub laden und Container starten -
   **nichts wird lokal gebaut**
5. Modelle im Hintergrund laden (`ollama-init`)
6. Warten bis die App antwortet → Browser öffnen

## Danach

| | |
|---|---|
| Studio | http://localhost:8181 |
| Login | standardmäßig **AUS** - Studio startet direkt |
| Login aktivieren | System → Zugriff → „Login erforderlich" (wirkt nach Neustart) |
| Zugang mit Login | `admin` / `admin` - **Standard-Passwort unter „Benutzer" ändern!** |
| Von unterwegs | Studio → System → Telegram-Bot → Token eintragen → QR scannen |

## Betrieb

- **Starten**: Verknüpfung „Summarizer starten" oder Docker Desktop → Gruppe **summarizer**
- **Stoppen**: Docker Desktop → Gruppe stoppen - Daten bleiben erhalten
- **Update**: Installer erneut ausführen - lädt das neueste Image, ersetzt nur die App;
  Datenbank, Dateien und Modelle bleiben, Migrationen laufen automatisch
- **Backup**: Studio → System → „Backup als ZIP herunterladen"

Daten liegen in Docker-Volumes und überleben Neustarts, Updates und Stoppen.
Gelöscht werden sie nur, wenn du in Docker Desktop die Gruppe **samt Volumes** entfernst.

## Für Fortgeschrittene (Kommandozeile)

```bash
docker compose --profile app --profile local-llm ps         # Status
docker compose --profile app --profile local-llm logs -f    # Logs
docker compose --profile whisper up -d                      # Audio-Transkription
docker exec summarizer-postgres pg_dump -U summarizer summarizer > backup.sql
```
