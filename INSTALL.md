# Summarizer installieren

Alles läuft lokal in Docker. Zugriff von unterwegs geht über den Telegram-Bot —
kein Tunnel, keine Portfreigabe, keine Cloud nötig.

## Windows

1. Ordner entpacken
2. Doppelklick auf **`install.bat`**
3. Fragen beantworten (Sprache, LLM, Admin-Passwort)
4. Browser öffnet automatisch **http://localhost:8181**

Fehlt Docker Desktop, installiert das Skript es per `winget` — danach Docker
einmal starten und `install.bat` erneut ausführen.

## Linux / macOS

```bash
chmod +x install.sh
./install.sh
```

Fehlt Docker, installiert das Skript es unter Linux automatisch
(`get.docker.com`); unter macOS kommt ein Hinweis auf Docker Desktop.

## Was der Installer macht

1. Docker prüfen / installieren, Engine starten
2. Fragen: Sprache der Inhalte (bestimmt das Embedding-Modell), lokales LLM ja/nein,
   Admin-Passwort — RAM-Erkennung wählt das passende Chat-Modell
3. Freien Port suchen (Standard **8181**, sonst nächster freier)
4. `.env` schreiben, fertige Images von Docker Hub laden
   (`mtprause/summarizer`) und Container starten — **nichts wird lokal gebaut**
5. Modelle im Hintergrund laden (`ollama-init`)
6. Warten bis die App antwortet → Browser öffnen

## Danach

| | |
|---|---|
| Studio | http://localhost:8181 |
| Login | `admin` + Passwort aus `.env`, sonst `docker logs summarizer-app` |
| Von unterwegs | Studio → System → Telegram-Bot → Token eintragen → QR scannen |
| Audio-Transkription | `docker compose --profile whisper up -d` |

## Betrieb

```bash
docker compose --profile app --profile local-llm ps        # Status
docker compose --profile app --profile local-llm logs -f   # Logs
docker compose --profile app --profile local-llm down      # stoppen (Daten bleiben)
docker compose --profile app --profile local-llm up -d     # wieder starten
```

Daten liegen in Docker-Volumes (`summarizer_pgdata`, `summarizer_files`,
`summarizer_ollama`) und überleben Neustarts, Updates und `down`.
Erst `down -v` löscht sie.

**Backup:**

```bash
docker exec summarizer-postgres pg_dump -U summarizer summarizer > backup.sql
```

## Update

```bash
docker compose --profile app --profile local-llm pull
docker compose --profile app --profile local-llm up -d
```

Zieht das neueste Image von Docker Hub und ersetzt nur den Container —
alle Daten bleiben (Docker-Volumes). Datenbank-Migrationen (Flyway)
laufen beim Start automatisch.
