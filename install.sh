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
