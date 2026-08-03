#!/usr/bin/env bash
# =====================================================================
#  Summarizer — grafischer Installations-Assistent (Linux / macOS)
#  Nutzt zenity (GTK). Ohne zenity faellt das Skript auf install.sh zurueck.
# =====================================================================
set -uo pipefail
cd "$(dirname "$0")"

TITLE="Summarizer Studio"

# ---------- Fallback ohne GUI ----------
if ! command -v zenity >/dev/null 2>&1; then
    echo "zenity nicht gefunden — starte Text-Installation."
    echo "(GUI nachruesten: sudo apt install zenity   /   sudo dnf install zenity)"
    exec ./install.sh
fi

info()  { zenity --info --title="$TITLE" --width=420 --text="$1"; }
error() { zenity --error --title="$TITLE" --width=420 --text="$1"; }

# ---------- 1. Docker ----------
if ! command -v docker >/dev/null 2>&1; then
    if [[ "$(uname)" == "Darwin" ]]; then
        error "Docker Desktop fehlt.\n\nBitte installieren:\n  brew install --cask docker\n\nDanach Docker starten und diesen Assistenten erneut ausfuehren."
        exit 1
    fi
    if zenity --question --title="$TITLE" --width=420 \
        --text="Docker ist nicht installiert.\n\nJetzt automatisch installieren? (benoetigt sudo)"; then
        (curl -fsSL https://get.docker.com | sudo sh) 2>&1 |
            zenity --progress --title="$TITLE" --text="Installiere Docker ..." --pulsate --auto-close --width=420
        sudo usermod -aG docker "$USER" || true
        info "Docker wurde installiert.\n\nBitte einmal ab- und wieder anmelden (Gruppenrechte), dann den Assistenten erneut starten."
        exit 0
    else
        exit 1
    fi
fi

if ! docker info >/dev/null 2>&1; then
    error "Die Docker-Engine laeuft nicht.\n\nBitte starten:  sudo systemctl start docker"
    exit 1
fi

# ---------- 2. Konfiguration ----------
CONFIG=$(zenity --forms --title="$TITLE" --width=480 \
    --text="Einstellungen fuer die Installation" \
    --add-combo="Sprache deiner Inhalte" --combo-values="Deutsch / gemischt|Englisch" \
    --add-combo="KI-Modelle" --combo-values="Lokal in Docker (empfohlen)|Vorhandenes Ollama nutzen" \
    --add-password="Admin-Passwort (leer = Standard 'admin')" \
    --add-entry="Port der Weboberflaeche" \
    --separator="|") || exit 1

LANG_CHOICE=$(echo "$CONFIG" | cut -d'|' -f1)
LLM_CHOICE=$(echo "$CONFIG" | cut -d'|' -f2)
ADMIN_PW=$(echo "$CONFIG" | cut -d'|' -f3)
APP_PORT=$(echo "$CONFIG" | cut -d'|' -f4)
APP_PORT=${APP_PORT:-8181}

WHISPER=no
zenity --question --title="$TITLE" --width=420 \
    --text="Audio-Transkription mitinstallieren?\n(Whisper, ca. 2 GB Download)" && WHISPER=yes

# ---------- 3. Port pruefen ----------
while (command -v ss >/dev/null && ss -ltn | grep -q ":${APP_PORT} ") ||
      (command -v lsof >/dev/null && lsof -iTCP:${APP_PORT} -sTCP:LISTEN >/dev/null 2>&1); do
    APP_PORT=$((APP_PORT + 1))
done

# ---------- 4. .env schreiben ----------
if [[ "$LANG_CHOICE" == "Englisch" ]]; then EMBED=nomic-embed-text; DIM=768
else EMBED=bge-m3; DIM=1024; fi

if [[ "$(uname)" == "Darwin" ]]; then
    RAM_GB=$(( $(sysctl -n hw.memsize) / 1024 / 1024 / 1024 ))
else
    RAM_GB=$(( $(grep MemTotal /proc/meminfo | awk '{print $2}') / 1024 / 1024 ))
fi
if (( RAM_GB >= 16 )); then CHAT=qwen3.5:9b; else CHAT=qwen3.5:4b; fi

if [[ "$LLM_CHOICE" == "Lokal in Docker (empfohlen)" ]]; then
    OLLAMA_URL="http://ollama:11434"; LOCAL_LLM=yes
else
    OLLAMA_URL="http://host.docker.internal:11434"; LOCAL_LLM=no
fi

cat > .env <<EOF
POSTGRES_DB=summarizer
POSTGRES_USER=summarizer
POSTGRES_PASSWORD=$(head -c 12 /dev/urandom | base64 | tr -dc 'a-zA-Z0-9' | head -c 16)
ADMIN_PASSWORD=${ADMIN_PW}
OLLAMA_BASE_URL=${OLLAMA_URL}
CHAT_MODEL=${CHAT}
EMBEDDING_MODEL=${EMBED}
EMBEDDING_DIM=${DIM}
APP_PORT=${APP_PORT}
WHISPER_MODEL=small
EOF

# ---------- 5. Starten ----------
PROFILES=(--profile app)
[[ "$LOCAL_LLM" == "yes" ]] && PROFILES+=(--profile local-llm)
[[ "$WHISPER" == "yes" ]] && PROFILES+=(--profile whisper)

(
    echo "10" ; echo "# Lade Programm-Images herunter ..."
    if ! docker compose "${PROFILES[@]}" pull > /tmp/summarizer-install.log 2>&1; then
        echo "100" ; echo "# FEHLER: Images konnten nicht geladen werden - Internetverbindung pruefen."
        exit 1
    fi
    echo "50" ; echo "# Starte Container ..."
    docker compose "${PROFILES[@]}" up -d >> /tmp/summarizer-install.log 2>&1
    echo "60" ; echo "# Warte auf die Anwendung ..."
    for i in $(seq 1 90); do
        if curl -sf -o /dev/null "http://localhost:${APP_PORT}/login"; then echo "100"; exit 0; fi
        echo "$(( 60 + i / 3 ))"
        sleep 3
    done
    exit 1
) | zenity --progress --title="$TITLE" --text="Installation laeuft ..." \
    --percentage=0 --auto-close --width=460

# Start-Verknuepfung im Anwendungsmenue
chmod +x ./summarizer-start.sh 2>/dev/null || true
DESKTOP_DIR="${HOME}/.local/share/applications"
mkdir -p "$DESKTOP_DIR"
cat > "${DESKTOP_DIR}/summarizer.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=Summarizer
Comment=Persoenliches Wissensarchiv starten
Exec=$(pwd)/summarizer-start.sh
Path=$(pwd)
Icon=applications-office
Terminal=false
Categories=Office;Utility;
DESKTOP
chmod +x "${DESKTOP_DIR}/summarizer.desktop" 2>/dev/null || true
update-desktop-database "$DESKTOP_DIR" >/dev/null 2>&1 || true

if curl -sf -o /dev/null "http://localhost:${APP_PORT}/login"; then
    PW_HINT="admin (Standard - bitte im Studio unter Benutzer aendern)"
    [[ -n "$ADMIN_PW" ]] && PW_HINT="(wie eingegeben)"
    info "Installation abgeschlossen.\n\nStudio:    http://localhost:${APP_PORT}\nBenutzer:  admin\nPasswort:  ${PW_HINT}"
    if command -v xdg-open >/dev/null; then xdg-open "http://localhost:${APP_PORT}" >/dev/null 2>&1 &
    elif command -v open >/dev/null; then open "http://localhost:${APP_PORT}"; fi
else
    error "Die Anwendung antwortet nicht.\n\nDetails:\n  docker logs summarizer-app\n  /tmp/summarizer-install.log"
    exit 1
fi
