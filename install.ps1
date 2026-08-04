# Summarizer Installer (Windows)
# Prüft/installiert Docker Desktop, fragt Konfiguration ab, startet den Stack.
$ErrorActionPreference = "Stop"
Write-Host "=== Summarizer Installation ===" -ForegroundColor Cyan

# --- 0. WSL 2 prüfen / installieren (Docker Desktop braucht es als Backend) ---
$wslOk = $false
try {
    wsl.exe --status *>$null
    if ($LASTEXITCODE -eq 0) { $wslOk = $true }
} catch {}
if (-not $wslOk) {
    Write-Host "WSL 2 fehlt - installiere Windows-Subsystem für Linux (braucht Adminrechte)..."
    try {
        Start-Process wsl.exe -ArgumentList "--install --no-distribution" -Verb RunAs -Wait
    } catch {
        Write-Host "WSL-Installation abgebrochen oder fehlgeschlagen." -ForegroundColor Red
        Write-Host "Manuell: PowerShell als Administrator öffnen -> 'wsl --install --no-distribution'" -ForegroundColor Yellow
        exit 1
    }
    Write-Host ""
    Write-Host "WSL wurde installiert. Bitte den Rechner NEU STARTEN" -ForegroundColor Yellow
    Write-Host "und dieses Skript danach erneut ausführen." -ForegroundColor Yellow
    exit 0
}
Write-Host "WSL 2 OK."

# --- 1. Docker prüfen / installieren ---
$docker = Get-Command docker -ErrorAction SilentlyContinue
if (-not $docker) {
    Write-Host "Docker nicht gefunden - installiere Docker Desktop via winget..."
    winget install -e --id Docker.DockerDesktop --accept-source-agreements --accept-package-agreements
    Write-Host "Docker Desktop installiert. Bitte einmal starten (und ggf. Rechner neu starten)," -ForegroundColor Yellow
    Write-Host "dann dieses Skript erneut ausführen." -ForegroundColor Yellow
    exit 0
}
try { docker info *>$null } catch {}
if ($LASTEXITCODE -ne 0) {
    Write-Host "Docker-Engine läuft nicht - starte Docker Desktop..."
    Start-Process "$env:ProgramFiles\Docker\Docker\Docker Desktop.exe"
    $tries = 0
    while ($tries -lt 60) {
        try { docker info *>$null } catch {}
        if ($LASTEXITCODE -eq 0) { break }
        Start-Sleep -Seconds 3; $tries++
    }
    if ($LASTEXITCODE -ne 0) { Write-Host "Docker startet nicht - bitte manuell prüfen." -ForegroundColor Red; exit 1 }
}
Write-Host "Docker OK." -ForegroundColor Green

# --- 1b. docker-compose.yml erzeugen, falls nicht vorhanden (Ein-Datei-Installation) ---
# Inhalt gespiegelt aus docker-compose.yml im Repo - bei Änderungen synchron halten.
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
# Zugriff von unterwegs läuft über den Telegram-Bot (Long-Polling, kein Tunnel nötig).

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
      CHAT_MODEL: ${CHAT_MODEL:-qwen3.5:4b}
      EMBEDDING_MODEL: ${EMBEDDING_MODEL:-bge-m3}
      EMBEDDING_DIM: ${EMBEDDING_DIM:-1024}
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

  # One-Shot: lädt Chat- und Embedding-Modell automatisch beim ersten Start
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
       ollama pull ${EMBEDDING_MODEL:-bge-m3}"
    restart: "no"

  # Whisper: Audio-Transkription (Sprachnachrichten aus der App)
  whisper:
    profiles: ["whisper"]
    image: onerahmet/openai-whisper-asr-webservice:latest
    container_name: summarizer-whisper
    ports:
      # Nur localhost — für Entwicklung ausserhalb des Containers (mvn spring-boot:run)
      - "127.0.0.1:${WHISPER_PORT:-9000}:9000"
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

# GPU-Override: wird nur eingebunden, wenn OLLAMA_GPU=1 in .env steht
if (-not (Test-Path "docker-compose.gpu.yml")) {
    @'
services:
  ollama:
    deploy:
      resources:
        reservations:
          devices:
            - driver: nvidia
              count: all
              capabilities: [gpu]
'@ | Out-File -Encoding utf8 "docker-compose.gpu.yml"
}

# --- 2. Konfiguration abfragen ---
if (-not (Test-Path ".env")) {
    Write-Host ""
    # Standard-Modelle: mehrsprachig, laufen auch auf kleinen GPUs gemeinsam im VRAM.
    # Beides im Studio unter "KI-Modelle" änderbar.
    $embedModel = "bge-m3"; $embedDim = 1024
    $chatModel = "qwen3.5:4b"
    Write-Host "Modelle: Chat $chatModel, Embeddings $embedModel (änderbar im Studio unter 'KI-Modelle')"

    $localLlm = Read-Host "Lokales LLM (Ollama im Container) verwenden? [J/n]"
    $useLocalLlm = $localLlm -ne "n"

    # Admin-Zugang: Standard admin/admin - Passwort im Studio unter Benutzer änderbar
    $adminPw = ""

    # Freien App-Port finden (Standard 8181, bei Belegung hochzählen)
    $appPort = 8181
    while (Get-NetTCPConnection -LocalPort $appPort -State Listen -ErrorAction SilentlyContinue) {
        $appPort++
    }
    if ($appPort -ne 8181) { Write-Host "Port 8181 belegt -> verwende $appPort" }

    # Freien Host-Port für den Ollama-Container finden - ein bereits auf dem
    # Host installiertes Ollama belegt sonst 11434 und der Container startet nicht.
    # (Innerhalb des Container-Netzes reden die Dienste unabhängig davon direkt.)
    $ollamaPort = 11434
    while (Get-NetTCPConnection -LocalPort $ollamaPort -State Listen -ErrorAction SilentlyContinue) {
        $ollamaPort++
    }
    if ($ollamaPort -ne 11434) {
        Write-Host "Port 11434 belegt (lokales Ollama?) -> Container-Port $ollamaPort"
    }

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
OLLAMA_PORT=$ollamaPort
WHISPER_MODEL=small
"@ | Out-File -Encoding utf8 ".env"
    Write-Host ".env geschrieben." -ForegroundColor Green
} else {
    Write-Host ".env existiert bereits - verwende bestehende Konfiguration."
    $envRaw = Get-Content ".env" -Raw
    $chatCfg = if ($envRaw -match "CHAT_MODEL=(\S+)") { $Matches[1] } else { "?" }
    $embedCfg = if ($envRaw -match "EMBEDDING_MODEL=(\S+)") { $Matches[1] } else { "?" }
    Write-Host "Konfigurierte Modelle: Chat $chatCfg, Embeddings $embedCfg"
    Write-Host "(ändern: .env bearbeiten oder im Studio unter 'KI-Modelle')"
}

# --- 2b. GPU einmalig erkennen: nutzbare NVIDIA-GPU -> Ollama mit GPU starten ---
if ((Get-Content ".env" -Raw) -notmatch "OLLAMA_GPU=") {
    Write-Host "Prüfe GPU-Unterstützung von Docker ..."
    docker run --rm --gpus=all alpine true *>$null
    $gpu = if ($LASTEXITCODE -eq 0) { "1" } else { "0" }
    Add-Content ".env" "OLLAMA_GPU=$gpu"
    if ($gpu -eq "1") {
        Write-Host "NVIDIA-GPU erkannt - Ollama nutzt die GPU." -ForegroundColor Green
    } else {
        Write-Host "Keine nutzbare GPU gefunden - Ollama läuft auf der CPU."
    }
}

# --- 3. Stack starten ---
$envContent = Get-Content ".env" -Raw
$profiles = @("--profile", "app")
if ($envContent -match "OLLAMA_BASE_URL=http://ollama") { $profiles += @("--profile", "local-llm") }
if ($envContent -match "WHISPER_MODEL=") { $profiles += @("--profile", "whisper") }
$composeFiles = @("-f", "docker-compose.yml")
if ($envContent -match "OLLAMA_GPU=1") { $composeFiles += @("-f", "docker-compose.gpu.yml") }
Write-Host "Lade Images von Docker Hub ..."
docker compose @composeFiles @profiles pull
if ($LASTEXITCODE -ne 0) {
    Write-Host "FEHLER: Images konnten nicht geladen werden - Internetverbindung prüfen." -ForegroundColor Red
    exit 1
}
docker compose @composeFiles @profiles up -d
if ($LASTEXITCODE -ne 0) { Write-Host "docker compose fehlgeschlagen." -ForegroundColor Red; exit 1 }

# --- 3b. Start-Skripte ablegen + Desktop-Verknüpfung "Summarizer" ---
if (-not (Test-Path "Summarizer-Start.ps1")) {
    @'
# =====================================================================
#  Summarizer starten — Doppelklick-Skript
#  Startet Docker (falls nötig), die Container und öffnet den Browser.
# =====================================================================
$ErrorActionPreference = "SilentlyContinue"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

Add-Type -AssemblyName System.Windows.Forms

function Get-EnvValue($key, $fallback) {
    $line = Select-String -Path ".env" -Pattern "^$key=(.*)$" -ErrorAction SilentlyContinue
    if ($line) { return $line.Matches[0].Groups[1].Value.Trim() }
    return $fallback
}

$port = Get-EnvValue "APP_PORT" "8181"
$url = "http://localhost:$port"

# Läuft es schon? Dann direkt öffnen.
try {
    $r = Invoke-WebRequest "$url/login" -TimeoutSec 2 -UseBasicParsing
    if ($r.StatusCode -eq 200) { Start-Process $url; exit 0 }
} catch { }

# Tray-Hinweis während des Starts
$notify = New-Object System.Windows.Forms.NotifyIcon
$notify.Icon = [System.Drawing.SystemIcons]::Information
$notify.Visible = $true
$notify.ShowBalloonTip(4000, "Summarizer", "Wird gestartet ...", "Info")

# Docker-Engine sicherstellen
docker info *>$null
if ($LASTEXITCODE -ne 0) {
    Start-Process "$env:ProgramFiles\Docker\Docker\Docker Desktop.exe"
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Seconds 3
        docker info *>$null
        if ($LASTEXITCODE -eq 0) { break }
    }
}

# Profile aus der Konfiguration ableiten
$profiles = @("--profile", "app")
if ((Get-Content ".env" -Raw) -match "OLLAMA_BASE_URL=http://ollama") { $profiles += @("--profile", "local-llm") }
if ((Get-Content ".env" -Raw) -match "WHISPER_MODEL=") { $profiles += @("--profile", "whisper") }
$composeFiles = @("-f", "docker-compose.yml")
if (((Get-Content ".env" -Raw) -match "OLLAMA_GPU=1") -and (Test-Path "docker-compose.gpu.yml")) {
    $composeFiles += @("-f", "docker-compose.gpu.yml")
}

docker compose @composeFiles @profiles up -d *>$null

# Auf die App warten, dann Browser öffnen
for ($i = 0; $i -lt 90; $i++) {
    try {
        $r = Invoke-WebRequest "$url/login" -TimeoutSec 2 -UseBasicParsing
        if ($r.StatusCode -eq 200) {
            $notify.ShowBalloonTip(3000, "Summarizer", "Bereit — Browser wird geöffnet.", "Info")
            Start-Process $url
            Start-Sleep -Seconds 3
            $notify.Dispose()
            exit 0
        }
    } catch { }
    Start-Sleep -Seconds 2
}

[System.Windows.Forms.MessageBox]::Show(
    "Summarizer antwortet nicht.`n`nLogs prüfen:  docker logs summarizer-app",
    "Summarizer", "OK", "Warning") | Out-Null
$notify.Dispose()
'@ | Out-File -Encoding utf8 "Summarizer-Start.ps1"
}
if (-not (Test-Path "Summarizer-Start.bat")) {
    @'
@echo off
REM Summarizer starten (Doppelklick) — startet Container und öffnet den Browser
cd /d "%~dp0"
start "" powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%~dp0Summarizer-Start.ps1"
'@ | Out-File -Encoding ascii "Summarizer-Start.bat"
}
try {
    $ws = New-Object -ComObject WScript.Shell
    $desktop = [Environment]::GetFolderPath("Desktop")
    $lnk = $ws.CreateShortcut((Join-Path $desktop "Summarizer.lnk"))
    $lnk.TargetPath = (Resolve-Path "Summarizer-Start.bat").Path
    $lnk.WorkingDirectory = (Get-Location).Path
    $lnk.Description = "Summarizer Studio starten"
    $lnk.Save()
    Write-Host "Desktop-Verknüpfung 'Summarizer' angelegt." -ForegroundColor Green
} catch { Write-Host "Desktop-Verknüpfung konnte nicht angelegt werden." }

# --- 4. Auf App warten, dann Browser öffnen ---
$port = ([regex]::Match($envContent, "APP_PORT=(\d+)")).Groups[1].Value
if (-not $port) { $port = 8181 }
Write-Host "Warte auf die App (Modelle lädt der ollama-init-Container im Hintergrund) ..."
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
Write-Host "Login:        standardmäßig AUS (aktivierbar: Studio -> System -> Zugriff)"
Write-Host "Admin-Login:  admin / admin (Passwort im Studio unter Benutzer ändern)"
if ($ready) {
    Start-Process "http://localhost:$port"
} else {
    Write-Host "App braucht noch einen Moment - Seite gleich manuell öffnen: http://localhost:$port" -ForegroundColor Yellow
}
