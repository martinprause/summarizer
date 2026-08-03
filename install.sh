#!/usr/bin/env bash
# Summarizer Installer (Linux/macOS)
set -euo pipefail
echo "=== Summarizer Installation ==="

# --- 1. Docker pruefen / installieren ---
if ! command -v docker >/dev/null 2>&1; then
    if [[ "$(uname)" == "Darwin" ]]; then
        echo "Docker fehlt. Installation (braucht Homebrew): brew install --cask docker"
        echo "Danach Docker.app starten und dieses Skript erneut ausfuehren."
        exit 1
    else
        echo "Docker fehlt - installiere via get.docker.com ..."
        curl -fsSL https://get.docker.com | sh
        sudo usermod -aG docker "$USER" || true
        echo "Ggf. neu einloggen (docker-Gruppe), dann Skript erneut ausfuehren."
    fi
fi
docker info >/dev/null 2>&1 || { echo "Docker-Engine laeuft nicht - bitte starten."; exit 1; }
echo "Docker OK."

# --- 1b. docker-compose.yml erzeugen, falls nicht vorhanden (Ein-Datei-Installation) ---
# Inhalt gespiegelt aus docker-compose.yml im Repo - bei Aenderungen synchron halten.
if [[ ! -f docker-compose.yml ]]; then
    echo "Erzeuge docker-compose.yml ..."
    cat > docker-compose.yml <<'COMPOSE_EOF'
# Profile:
#   (ohne)      -> nur Postgres (lokale Entwicklung, App aus IDE/Maven)
#   app         -> komplette App im Container
#   local-llm   -> Ollama-Container (Modelle via 'ollama pull' im Container)
#
# Komplett-Start:  docker compose --profile app --profile local-llm --profile whisper up -d
# (App kommt als fertiges Image von Docker Hub; Entwickler bauen mit:
#  docker build -t mtprause/summarizer:latest .)
#
# Zugriff von unterwegs laeuft ueber den Telegram-Bot (Long-Polling, kein Tunnel noetig).

services:
  postgres:
    image: pgvector/pgvector:pg17
    container_name: summarizer-postgres
    environment:
      POSTGRES_DB: ${POSTGRES_DB:-summarizer}
      POSTGRES_USER: ${POSTGRES_USER:-summarizer}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-summarizer}
    ports:
      # Nur localhost (Entwicklung); 5433 weil lokale PostgreSQL-Installation ggf. 5432 belegt
      - "127.0.0.1:5433:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER:-summarizer} -d ${POSTGRES_DB:-summarizer}"]
      interval: 5s
      timeout: 3s
      retries: 10

  app:
    profiles: ["app"]
    # Fertiges Image von Docker Hub — Enduser bauen nichts selbst.
    # Eigenes Image: APP_IMAGE in .env setzen.
    image: ${APP_IMAGE:-mtprause/summarizer:latest}
    pull_policy: ${APP_PULL_POLICY:-missing}
    container_name: summarizer-app
    restart: unless-stopped
    ports:
      # Einziger nach aussen sichtbarer Port (APP_PORT in .env, Default 8181)
      - "${APP_PORT:-8181}:8080"
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/${POSTGRES_DB:-summarizer}
      SPRING_DATASOURCE_USERNAME: ${POSTGRES_USER:-summarizer}
      SPRING_DATASOURCE_PASSWORD: ${POSTGRES_PASSWORD:-summarizer}
      OLLAMA_BASE_URL: ${OLLAMA_BASE_URL:-http://ollama:11434}
      CHAT_MODEL: ${CHAT_MODEL:-llama3.2:latest}
      EMBEDDING_MODEL: ${EMBEDDING_MODEL:-nomic-embed-text}
      EMBEDDING_DIM: ${EMBEDDING_DIM:-768}
      ADMIN_PASSWORD: ${ADMIN_PASSWORD:-}
      FILES_DIR: /data/files
      WHISPER_BASE_URL: http://whisper:9000
      VAADIN_LAUNCH_BROWSER: "false"
    volumes:
      - files:/data/files
    depends_on:
      postgres:
        condition: service_healthy

  ollama:
    profiles: ["local-llm"]
    image: ollama/ollama:latest
    container_name: summarizer-ollama
    ports:
      # Nur localhost — im Container-Netz reden die Dienste direkt miteinander
      - "127.0.0.1:${OLLAMA_PORT:-11434}:11434"
    volumes:
      - ollama:/root/.ollama

  # One-Shot: laedt Chat- und Embedding-Modell automatisch beim ersten Start
  ollama-init:
    profiles: ["local-llm"]
    image: ollama/ollama:latest
    container_name: summarizer-ollama-init
    depends_on:
      - ollama
    environment:
      OLLAMA_HOST: http://ollama:11434
    entrypoint: ["/bin/sh", "-c"]
    command: >
      "until ollama list >/dev/null 2>&1; do sleep 2; done &&
       ollama pull ${CHAT_MODEL:-qwen3.5:4b} &&
       ollama pull ${EMBEDDING_MODEL:-nomic-embed-text}"
    restart: "no"

  # Whisper: Audio-Transkription (Sprachnachrichten aus der App)
  whisper:
    profiles: ["whisper"]
    image: onerahmet/openai-whisper-asr-webservice:latest
    container_name: summarizer-whisper
    environment:
      ASR_MODEL: ${WHISPER_MODEL:-small}
      ASR_ENGINE: faster_whisper
    volumes:
      - whisper:/root/.cache
    restart: unless-stopped


volumes:
  pgdata:
  ollama:
  files:
  whisper:
COMPOSE_EOF
fi

# --- 2. Konfiguration abfragen ---
if [[ ! -f .env ]]; then
    read -rp "Sprache deiner Inhalte? [1] Deutsch/gemischt (Standard) [2] Englisch: " lang
    if [[ "${lang:-1}" == "2" ]]; then EMBED_MODEL=nomic-embed-text; EMBED_DIM=768
    else EMBED_MODEL=bge-m3; EMBED_DIM=1024; fi

    read -rp "Lokales LLM (Ollama im Container) verwenden? [J/n]: " locallm
    USE_LOCAL_LLM=$([[ "${locallm:-j}" == "n" ]] && echo no || echo yes)

    if [[ "$(uname)" == "Darwin" ]]; then
        RAM_GB=$(( $(sysctl -n hw.memsize) / 1024 / 1024 / 1024 ))
    else
        RAM_GB=$(( $(grep MemTotal /proc/meminfo | awk '{print $2}') / 1024 / 1024 ))
    fi
    if (( RAM_GB >= 16 )); then CHAT_MODEL=qwen3.5:9b; else CHAT_MODEL=qwen3.5:4b; fi
    echo "RAM: ${RAM_GB} GB -> Chat-Modell: ${CHAT_MODEL} (aenderbar im Studio unter 'KI-Modelle')"

    read -rp "Admin-Passwort (leer = Standard 'admin', im Studio aenderbar): " ADMIN_PW

    # Freien App-Port finden (Standard 8181)
    APP_PORT=8181
    while (command -v ss >/dev/null && ss -ltn | grep -q ":${APP_PORT} ") ||
          (command -v lsof >/dev/null && lsof -iTCP:${APP_PORT} -sTCP:LISTEN >/dev/null 2>&1); do
        APP_PORT=$((APP_PORT + 1))
    done
    [[ "$APP_PORT" != "8181" ]] && echo "Port 8181 belegt -> verwende ${APP_PORT}"

    OLLAMA_URL=$([[ "$USE_LOCAL_LLM" == "yes" ]] && echo "http://ollama:11434" || echo "http://host.docker.internal:11434")
    cat > .env <<EOF
POSTGRES_DB=summarizer
POSTGRES_USER=summarizer
POSTGRES_PASSWORD=$(head -c 12 /dev/urandom | base64 | tr -dc 'a-zA-Z0-9' | head -c 16)
ADMIN_PASSWORD=${ADMIN_PW}
OLLAMA_BASE_URL=${OLLAMA_URL}
CHAT_MODEL=${CHAT_MODEL}
EMBEDDING_MODEL=${EMBED_MODEL}
EMBEDDING_DIM=${EMBED_DIM}
APP_PORT=${APP_PORT}
EOF
    echo ".env geschrieben."
else
    echo ".env existiert bereits - verwende bestehende Konfiguration."
fi

# --- 3. Stack starten ---
PROFILES=(--profile app)
grep -q "OLLAMA_BASE_URL=http://ollama" .env && PROFILES+=(--profile local-llm)
echo "Lade Images von Docker Hub ..."
if ! docker compose "${PROFILES[@]}" pull; then
    echo "FEHLER: Images konnten nicht geladen werden - Internetverbindung pruefen." >&2
    exit 1
fi
docker compose "${PROFILES[@]}" up -d

# --- 4. Auf App warten, dann Browser oeffnen ---
PORT=$(grep -E "^APP_PORT=" .env | cut -d= -f2)
PORT=${PORT:-8181}
echo "Warte auf die App (Modelle laedt der ollama-init-Container im Hintergrund) ..."
READY=no
for i in $(seq 1 60); do
    if curl -sf -o /dev/null "http://localhost:${PORT}/login"; then READY=yes; break; fi
    sleep 5
done

echo ""
echo "=== Fertig! ==="
echo "Studio:       http://localhost:${PORT}"
echo "Login:        standardmaessig AUS (aktivierbar: Studio -> System -> Zugriff)"
echo "Admin-Login:  admin / admin (falls kein eigenes Passwort gesetzt - bitte unter Benutzer aendern)"
if [[ "$READY" == "yes" ]]; then
    if command -v xdg-open >/dev/null; then xdg-open "http://localhost:${PORT}" >/dev/null 2>&1 &
    elif command -v open >/dev/null; then open "http://localhost:${PORT}"; fi
else
    echo "App braucht noch einen Moment - Seite gleich manuell oeffnen."
fi
