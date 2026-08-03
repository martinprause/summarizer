# Summarizer Installer (Windows)
# Prueft/installiert Docker Desktop, fragt Konfiguration ab, startet den Stack.
$ErrorActionPreference = "Stop"
Write-Host "=== Summarizer Installation ===" -ForegroundColor Cyan

# --- 0. WSL 2 pruefen / installieren (Docker Desktop braucht es als Backend) ---
$wslOk = $false
try {
    wsl.exe --status *>$null
    if ($LASTEXITCODE -eq 0) { $wslOk = $true }
} catch {}
if (-not $wslOk) {
    Write-Host "WSL 2 fehlt - installiere Windows-Subsystem fuer Linux (braucht Adminrechte)..."
    try {
        Start-Process wsl.exe -ArgumentList "--install --no-distribution" -Verb RunAs -Wait
    } catch {
        Write-Host "WSL-Installation abgebrochen oder fehlgeschlagen." -ForegroundColor Red
        Write-Host "Manuell: PowerShell als Administrator oeffnen -> 'wsl --install --no-distribution'" -ForegroundColor Yellow
        exit 1
    }
    Write-Host ""
    Write-Host "WSL wurde installiert. Bitte den Rechner NEU STARTEN" -ForegroundColor Yellow
    Write-Host "und dieses Skript danach erneut ausfuehren." -ForegroundColor Yellow
    exit 0
}
Write-Host "WSL 2 OK."

# --- 1. Docker pruefen / installieren ---
$docker = Get-Command docker -ErrorAction SilentlyContinue
if (-not $docker) {
    Write-Host "Docker nicht gefunden - installiere Docker Desktop via winget..."
    winget install -e --id Docker.DockerDesktop --accept-source-agreements --accept-package-agreements
    Write-Host "Docker Desktop installiert. Bitte einmal starten (und ggf. Rechner neu starten)," -ForegroundColor Yellow
    Write-Host "dann dieses Skript erneut ausfuehren." -ForegroundColor Yellow
    exit 0
}
try { docker info *>$null } catch {}
if ($LASTEXITCODE -ne 0) {
    Write-Host "Docker-Engine laeuft nicht - starte Docker Desktop..."
    Start-Process "$env:ProgramFiles\Docker\Docker\Docker Desktop.exe"
    $tries = 0
    while ($tries -lt 60) {
        try { docker info *>$null } catch {}
        if ($LASTEXITCODE -eq 0) { break }
        Start-Sleep -Seconds 3; $tries++
    }
    if ($LASTEXITCODE -ne 0) { Write-Host "Docker startet nicht - bitte manuell pruefen." -ForegroundColor Red; exit 1 }
}
Write-Host "Docker OK." -ForegroundColor Green

# --- 1b. docker-compose.yml erzeugen, falls nicht vorhanden (Ein-Datei-Installation) ---
# Inhalt gespiegelt aus docker-compose.yml im Repo - bei Aenderungen synchron halten.
if (-not (Test-Path "docker-compose.yml")) {
    Write-Host "Erzeuge docker-compose.yml ..."
    @'
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
'@ | Out-File -Encoding utf8 "docker-compose.yml"
}

# --- 2. Konfiguration abfragen ---
if (-not (Test-Path ".env")) {
    Write-Host ""
    $lang = Read-Host "Sprache deiner Inhalte? [1] Deutsch/gemischt (Standard)  [2] Englisch"
    if ($lang -eq "2") { $embedModel = "nomic-embed-text"; $embedDim = 768 }
    else { $embedModel = "bge-m3"; $embedDim = 1024 }

    $localLlm = Read-Host "Lokales LLM (Ollama im Container) verwenden? [J/n]"
    $useLocalLlm = $localLlm -ne "n"

    $ram = [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB)
    if ($ram -ge 16) { $chatModel = "qwen3.5:9b" } else { $chatModel = "qwen3.5:4b" }
    Write-Host "RAM: $ram GB -> Chat-Modell: $chatModel (aenderbar im Studio unter 'KI-Modelle')"

    $adminPw = Read-Host "Admin-Passwort (leer = Standard 'admin', im Studio aenderbar)"

    # Freien App-Port finden (Standard 8181, bei Belegung hochzaehlen)
    $appPort = 8181
    while (Get-NetTCPConnection -LocalPort $appPort -State Listen -ErrorAction SilentlyContinue) {
        $appPort++
    }
    if ($appPort -ne 8181) { Write-Host "Port 8181 belegt -> verwende $appPort" }

    $ollamaUrl = if ($useLocalLlm) { "http://ollama:11434" } else { "http://host.docker.internal:11434" }
    @"
POSTGRES_DB=summarizer
POSTGRES_USER=summarizer
POSTGRES_PASSWORD=$([guid]::NewGuid().ToString('N').Substring(0,16))
ADMIN_PASSWORD=$adminPw
OLLAMA_BASE_URL=$ollamaUrl
CHAT_MODEL=$chatModel
EMBEDDING_MODEL=$embedModel
EMBEDDING_DIM=$embedDim
APP_PORT=$appPort
"@ | Out-File -Encoding utf8 ".env"
    Write-Host ".env geschrieben." -ForegroundColor Green
} else {
    Write-Host ".env existiert bereits - verwende bestehende Konfiguration."
}

# --- 3. Stack starten ---
$envContent = Get-Content ".env" -Raw
$profiles = @("--profile", "app")
if ($envContent -match "OLLAMA_BASE_URL=http://ollama") { $profiles += @("--profile", "local-llm") }
Write-Host "Lade Images von Docker Hub ..."
docker compose @profiles pull
if ($LASTEXITCODE -ne 0) {
    Write-Host "FEHLER: Images konnten nicht geladen werden - Internetverbindung pruefen." -ForegroundColor Red
    exit 1
}
docker compose @profiles up -d
if ($LASTEXITCODE -ne 0) { Write-Host "docker compose fehlgeschlagen." -ForegroundColor Red; exit 1 }

# --- 4. Auf App warten, dann Browser oeffnen ---
$port = ([regex]::Match($envContent, "APP_PORT=(\d+)")).Groups[1].Value
if (-not $port) { $port = 8181 }
Write-Host "Warte auf die App (Modelle laedt der ollama-init-Container im Hintergrund) ..."
$ready = $false
for ($i = 0; $i -lt 60; $i++) {
    try {
        $r = Invoke-WebRequest "http://localhost:$port/login" -TimeoutSec 3 -UseBasicParsing
        if ($r.StatusCode -eq 200) { $ready = $true; break }
    } catch { Start-Sleep -Seconds 5 }
}

# --- 5. Fertig ---
Write-Host ""
Write-Host "=== Fertig! ===" -ForegroundColor Cyan
Write-Host "Studio:       http://localhost:$port"
Write-Host "Login:        standardmaessig AUS (aktivierbar: Studio -> System -> Zugriff)"
Write-Host "Admin-Login:  admin / admin (falls kein eigenes Passwort gesetzt - bitte unter Benutzer aendern)"
if ($ready) {
    Start-Process "http://localhost:$port"
} else {
    Write-Host "App braucht noch einen Moment - Seite gleich manuell oeffnen: http://localhost:$port" -ForegroundColor Yellow
}
