#!/usr/bin/env bash
# =====================================================================
#  Summarizer starten — startet Container und oeffnet den Browser
# =====================================================================
cd "$(dirname "$0")"

PORT=$(grep -E "^APP_PORT=" .env 2>/dev/null | cut -d= -f2)
PORT=${PORT:-8181}
URL="http://localhost:${PORT}"

open_browser() {
    if command -v xdg-open >/dev/null; then xdg-open "$URL" >/dev/null 2>&1 &
    elif command -v open >/dev/null; then open "$URL"; fi
}

# Laeuft es schon?
if curl -sf -o /dev/null "${URL}/login"; then open_browser; exit 0; fi

if ! docker info >/dev/null 2>&1; then
    echo "Docker-Engine startet nicht automatisch. Bitte starten:  sudo systemctl start docker"
    exit 1
fi

PROFILES=(--profile app)
grep -q "OLLAMA_BASE_URL=http://ollama" .env 2>/dev/null && PROFILES+=(--profile local-llm)
grep -q "WHISPER_MODEL=" .env 2>/dev/null && PROFILES+=(--profile whisper)

echo "Starte Summarizer ..."
docker compose "${PROFILES[@]}" up -d >/dev/null

for i in $(seq 1 90); do
    if curl -sf -o /dev/null "${URL}/login"; then
        echo "Bereit — oeffne ${URL}"
        open_browser
        exit 0
    fi
    sleep 2
done

echo "Summarizer antwortet nicht. Logs:  docker logs summarizer-app"
exit 1
