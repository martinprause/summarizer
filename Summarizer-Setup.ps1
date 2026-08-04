# =====================================================================
#  Summarizer — grafischer Installations-Assistent (Windows)
#  Eigenständig: erzeugt docker-compose.yml, .env und Start-Skripte selbst.
#  Als EXE gebaut mit ps2exe (siehe README, Abschnitt Entwicklung).
# =====================================================================
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()

$accent = [System.Drawing.Color]::FromArgb(58, 74, 216)
$bg     = [System.Drawing.Color]::FromArgb(247, 248, 252)

# ---------------- Fenster ----------------
$form = New-Object System.Windows.Forms.Form
$form.Text = "Summarizer — Installation"
$form.Size = New-Object System.Drawing.Size(620, 560)
$form.StartPosition = "CenterScreen"
$form.BackColor = $bg
$form.FormBorderStyle = "FixedDialog"
$form.MaximizeBox = $false

# Kopfbereich
$header = New-Object System.Windows.Forms.Panel
$header.Size = New-Object System.Drawing.Size(620, 72)
$header.Location = New-Object System.Drawing.Point(0, 0)
$header.BackColor = $accent
$form.Controls.Add($header)

$logo = New-Object System.Windows.Forms.Label
$logo.Text = "S"
$logo.Font = New-Object System.Drawing.Font("Segoe UI", 20, [System.Drawing.FontStyle]::Bold)
$logo.ForeColor = [System.Drawing.Color]::White
$logo.Location = New-Object System.Drawing.Point(22, 16)
$logo.Size = New-Object System.Drawing.Size(40, 44)
$header.Controls.Add($logo)

$title = New-Object System.Windows.Forms.Label
$title.Text = "Summarizer Studio"
$title.Font = New-Object System.Drawing.Font("Segoe UI", 15, [System.Drawing.FontStyle]::Bold)
$title.ForeColor = [System.Drawing.Color]::White
$title.Location = New-Object System.Drawing.Point(62, 14)
$title.Size = New-Object System.Drawing.Size(400, 28)
$header.Controls.Add($title)

$subtitle = New-Object System.Windows.Forms.Label
$subtitle.Text = "Dein privates Wissensarchiv — läuft komplett lokal"
$subtitle.Font = New-Object System.Drawing.Font("Segoe UI", 9)
$subtitle.ForeColor = [System.Drawing.Color]::FromArgb(220, 225, 255)
$subtitle.Location = New-Object System.Drawing.Point(64, 42)
$subtitle.Size = New-Object System.Drawing.Size(460, 20)
$header.Controls.Add($subtitle)

function New-Label($text, $x, $y, $w, $bold = $false) {
    $l = New-Object System.Windows.Forms.Label
    $l.Text = $text
    $l.Location = New-Object System.Drawing.Point($x, $y)
    $l.Size = New-Object System.Drawing.Size($w, 20)
    $style = if ($bold) { [System.Drawing.FontStyle]::Bold } else { [System.Drawing.FontStyle]::Regular }
    $l.Font = New-Object System.Drawing.Font("Segoe UI", 9.5, $style)
    $form.Controls.Add($l)
    return $l
}

# ---------------- Einstellungen ----------------
New-Label "Sprache deiner Inhalte" 28 96 250 $true | Out-Null
$langBox = New-Object System.Windows.Forms.ComboBox
$langBox.Location = New-Object System.Drawing.Point(28, 118)
$langBox.Size = New-Object System.Drawing.Size(250, 26)
$langBox.DropDownStyle = "DropDownList"
[void]$langBox.Items.AddRange(@("Deutsch", "Englisch"))
$langBox.SelectedIndex = 0
$form.Controls.Add($langBox)

New-Label "KI-Modelle" 310 96 250 $true | Out-Null
$llmBox = New-Object System.Windows.Forms.ComboBox
$llmBox.Location = New-Object System.Drawing.Point(310, 118)
$llmBox.Size = New-Object System.Drawing.Size(270, 26)
$llmBox.DropDownStyle = "DropDownList"
[void]$llmBox.Items.AddRange(@("Lokal in Docker (empfohlen)", "Bereits installiertes Ollama nutzen"))
$llmBox.SelectedIndex = 0
$form.Controls.Add($llmBox)

# Admin-Zugang fest: admin/admin - Passwort im Studio unter Benutzer änderbar
New-Label "Port der Weboberfläche" 28 158 250 $true | Out-Null
$portBox = New-Object System.Windows.Forms.NumericUpDown
$portBox.Location = New-Object System.Drawing.Point(28, 180)
$portBox.Size = New-Object System.Drawing.Size(120, 26)
$portBox.Minimum = 1024
$portBox.Maximum = 65535
$portBox.Value = 8181
$form.Controls.Add($portBox)

New-Label "Installationsordner" 310 158 250 $true | Out-Null
$dirBox = New-Object System.Windows.Forms.TextBox
$dirBox.Location = New-Object System.Drawing.Point(310, 180)
$dirBox.Size = New-Object System.Drawing.Size(200, 26)
$dirBox.Text = Join-Path $env:USERPROFILE "Summarizer"
$form.Controls.Add($dirBox)
$browseButton = New-Object System.Windows.Forms.Button
$browseButton.Text = "..."
$browseButton.Location = New-Object System.Drawing.Point(516, 179)
$browseButton.Size = New-Object System.Drawing.Size(40, 27)
$form.Controls.Add($browseButton)
$browseButton.Add_Click({
    $dlg = New-Object System.Windows.Forms.FolderBrowserDialog
    $dlg.Description = "Installationsordner wählen"
    if ($dlg.ShowDialog() -eq "OK") { $dirBox.Text = $dlg.SelectedPath }
})

# Audio-Transkription (Whisper) ist immer dabei - keine Abfrage
$audioInfo = New-Label "Audio-Transkription (Whisper) wird mitinstalliert" 28 216 420 $false

# ---------------- Fortschritt ----------------
$statusLabel = New-Label "Bereit zur Installation." 28 254 540 | Out-Null
$statusLabel = $form.Controls[$form.Controls.Count - 1]

$progress = New-Object System.Windows.Forms.ProgressBar
$progress.Location = New-Object System.Drawing.Point(28, 278)
$progress.Size = New-Object System.Drawing.Size(552, 18)
$progress.Style = "Continuous"
$form.Controls.Add($progress)

$logBox = New-Object System.Windows.Forms.TextBox
$logBox.Location = New-Object System.Drawing.Point(28, 306)
$logBox.Size = New-Object System.Drawing.Size(552, 150)
$logBox.Multiline = $true
$logBox.ScrollBars = "Vertical"
$logBox.ReadOnly = $true
$logBox.BackColor = [System.Drawing.Color]::White
$logBox.Font = New-Object System.Drawing.Font("Consolas", 8.5)
$form.Controls.Add($logBox)

$installButton = New-Object System.Windows.Forms.Button
$installButton.Text = "Installieren"
$installButton.Location = New-Object System.Drawing.Point(438, 470)
$installButton.Size = New-Object System.Drawing.Size(142, 36)
$installButton.BackColor = $accent
$installButton.ForeColor = [System.Drawing.Color]::White
$installButton.FlatStyle = "Flat"
$installButton.FlatAppearance.BorderSize = 0
$installButton.Font = New-Object System.Drawing.Font("Segoe UI", 10, [System.Drawing.FontStyle]::Bold)
$form.Controls.Add($installButton)

$openButton = New-Object System.Windows.Forms.Button
$openButton.Text = "Studio öffnen"
$openButton.Location = New-Object System.Drawing.Point(286, 470)
$openButton.Size = New-Object System.Drawing.Size(142, 36)
$openButton.FlatStyle = "Flat"
$openButton.Enabled = $false
$openButton.Font = New-Object System.Drawing.Font("Segoe UI", 10)
$form.Controls.Add($openButton)

# ---------------- Logik ----------------
function Write-Log($text) {
    $logBox.AppendText("$text`r`n")
    $logBox.SelectionStart = $logBox.TextLength
    $logBox.ScrollToCaret()
    [System.Windows.Forms.Application]::DoEvents()
}

function Set-Status($text, $percent) {
    $statusLabel.Text = $text
    $progress.Value = [Math]::Min(100, [Math]::Max(0, $percent))
    [System.Windows.Forms.Application]::DoEvents()
}

function Test-PortFree($port) {
    -not (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
}

$installButton.Add_Click({
    $installButton.Enabled = $false
    try {
        # 0a. Installationsordner anlegen und dort arbeiten
        $installDir = $dirBox.Text.Trim()
        if (-not $installDir) { throw "Bitte Installationsordner angeben." }
        New-Item -ItemType Directory -Force -Path $installDir | Out-Null
        Set-Location $installDir
        Write-Log "Installationsordner: $installDir"

        # 0b. docker-compose.yml + Start-Skripte ablegen (eigenständige Installation)
        if (-not (Test-Path "docker-compose.yml")) {
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
      EMBEDDING_MODEL: ${EMBEDDING_MODEL:-qwen3-embedding:0.6b}
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
       ollama pull ${EMBEDDING_MODEL:-qwen3-embedding:0.6b}"
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
            Write-Log "docker-compose.yml erzeugt."
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
        # 0. WSL 2 (Docker Desktop braucht es als Backend)
        Set-Status "Prüfe WSL 2 ..." 3
        $wslOk = $false
        try {
            wsl.exe --status *>$null
            if ($LASTEXITCODE -eq 0) { $wslOk = $true }
        } catch {}
        if (-not $wslOk) {
            Write-Log "WSL 2 fehlt — Installation startet (Adminrechte-Abfrage folgt) ..."
            Set-Status "Installiere WSL 2 ..." 4
            try {
                Start-Process wsl.exe -ArgumentList "--install --no-distribution" -Verb RunAs -Wait
            } catch {
                throw "WSL-Installation abgebrochen. Manuell: PowerShell als Administrator -> 'wsl --install --no-distribution'"
            }
            [System.Windows.Forms.MessageBox]::Show(
                "WSL 2 wurde installiert. Bitte Windows neu starten und diesen Assistenten erneut ausführen.",
                "Neustart nötig", "OK", "Information") | Out-Null
            $installButton.Enabled = $true
            return
        }
        Write-Log "WSL 2 bereit."

        # 1. Docker
        Set-Status "Prüfe Docker ..." 5
        if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
            Write-Log "Docker nicht gefunden — starte Installation via winget ..."
            Set-Status "Installiere Docker Desktop (dauert einige Minuten) ..." 10
            winget install -e --id Docker.DockerDesktop --accept-source-agreements --accept-package-agreements 2>&1 |
                ForEach-Object { Write-Log $_ }
            [System.Windows.Forms.MessageBox]::Show(
                "Docker Desktop wurde installiert. Bitte Windows neu starten, Docker einmal öffnen und diesen Assistenten erneut ausführen.",
                "Neustart nötig", "OK", "Information") | Out-Null
            $installButton.Enabled = $true
            return
        }
        docker info *>$null
        if ($LASTEXITCODE -ne 0) {
            Write-Log "Docker-Engine startet ..."
            Set-Status "Starte Docker Desktop ..." 12
            Start-Process "$env:ProgramFiles\Docker\Docker\Docker Desktop.exe" -ErrorAction SilentlyContinue
            for ($i = 0; $i -lt 60; $i++) {
                docker info *>$null
                if ($LASTEXITCODE -eq 0) { break }
                Start-Sleep -Seconds 3
                [System.Windows.Forms.Application]::DoEvents()
            }
            if ($LASTEXITCODE -ne 0) { throw "Docker-Engine läuft nicht." }
        }
        Write-Log "Docker bereit."

        # 2. Port prüfen
        Set-Status "Prüfe Port ..." 18
        $port = [int]$portBox.Value
        while (-not (Test-PortFree $port)) {
            Write-Log "Port $port belegt — probiere $($port + 1)"
            $port++
        }
        $portBox.Value = $port
        Write-Log "Verwende Port $port."

        # 3. Konfiguration schreiben (bestehende .env bleibt erhalten)
        Set-Status "Schreibe Konfiguration ..." 24
        $envExists = Test-Path ".env"
        # Standard-Modelle: mehrsprachig, laufen auch auf kleinen GPUs gemeinsam im VRAM
        $embed = "qwen3-embedding:0.6b"; $dim = 1024
        $chat = "qwen3.5:4b"
        Write-Log "Modelle: Chat $chat, Embeddings $embed (änderbar im Studio unter 'KI-Modelle')"
        $ollamaUrl = if ($llmBox.SelectedIndex -eq 0) { "http://ollama:11434" }
                     else { "http://host.docker.internal:11434" }
        $dbPassword = [guid]::NewGuid().ToString('N').Substring(0, 16)
        @"
POSTGRES_DB=summarizer
POSTGRES_USER=summarizer
POSTGRES_PASSWORD=$dbPassword
ADMIN_PASSWORD=
OLLAMA_BASE_URL=$ollamaUrl
CHAT_MODEL=$chat
EMBEDDING_MODEL=$embed
EMBEDDING_DIM=$dim
APP_PORT=$port
WHISPER_MODEL=small
"@ | ForEach-Object { if (-not $envExists) { $_ | Out-File -Encoding utf8 ".env" } }
        if ($envExists) { Write-Log ".env existiert bereits - Konfiguration unverändert." }

        # 3b. GPU einmalig erkennen: nutzbare NVIDIA-GPU -> Ollama mit GPU starten
        if ((Get-Content ".env" -Raw) -notmatch "OLLAMA_GPU=") {
            Set-Status "Prüfe GPU-Unterstützung ..." 30
            docker run --rm --gpus=all alpine true *>$null
            $gpu = if ($LASTEXITCODE -eq 0) { "1" } else { "0" }
            Add-Content ".env" "OLLAMA_GPU=$gpu"
            if ($gpu -eq "1") { Write-Log "NVIDIA-GPU erkannt - Ollama nutzt die GPU." }
            else { Write-Log "Keine nutzbare GPU gefunden - Ollama läuft auf der CPU." }
        }

        # 4. Images laden
        $profiles = @("--profile", "app")
        if ($llmBox.SelectedIndex -eq 0) { $profiles += @("--profile", "local-llm") }
        $profiles += @("--profile", "whisper")
        $composeFiles = @("-f", "docker-compose.yml")
        if ((Get-Content ".env" -Raw) -match "OLLAMA_GPU=1") {
            $composeFiles += @("-f", "docker-compose.gpu.yml")
        }

        Set-Status "Lade Programm-Images herunter ..." 35
        Write-Log "Ziehe fertige Images von Docker Hub (kein lokaler Build) ..."
        docker compose @composeFiles @profiles pull 2>&1 | ForEach-Object { Write-Log $_ }
        if ($LASTEXITCODE -ne 0) {
            throw "Images konnten nicht geladen werden - Internetverbindung prüfen."
        }

        Set-Status "Starte Container ..." 55
        docker compose @composeFiles @profiles up -d 2>&1 | ForEach-Object { Write-Log $_ }
        if ($LASTEXITCODE -ne 0) { throw "Container-Start fehlgeschlagen." }

        # 4b. Start-Verknüpfungen anlegen
        Set-Status "Erstelle Verknüpfungen ..." 65
        try {
            $shell = New-Object -ComObject WScript.Shell
            foreach ($dir in @([Environment]::GetFolderPath("Desktop"),
                               (Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs"))) {
                $lnk = $shell.CreateShortcut((Join-Path $dir "Summarizer.lnk"))
                $lnk.TargetPath = Join-Path $installDir "Summarizer-Start.bat"
                $lnk.WorkingDirectory = $installDir
                $lnk.IconLocation = "$env:SystemRoot\System32\shell32.dll,14"
                $lnk.Description = "Summarizer Studio starten"
                $lnk.Save()
            }
            Write-Log "Verknüpfungen auf Desktop und im Startmenü angelegt."
        } catch { Write-Log "Verknüpfung konnte nicht angelegt werden: $_" }

        # 5. Warten
        Set-Status "Warte auf die Anwendung ..." 70
        $ready = $false
        for ($i = 0; $i -lt 90; $i++) {
            try {
                $r = Invoke-WebRequest "http://localhost:$port/login" -TimeoutSec 3 -UseBasicParsing
                if ($r.StatusCode -eq 200) { $ready = $true; break }
            } catch { }
            Start-Sleep -Seconds 3
            Set-Status "Warte auf die Anwendung ... ($($i * 3)s)" ([Math]::Min(95, 70 + $i / 3))
        }
        if (-not $ready) { throw "Anwendung antwortet nicht — Logs prüfen: docker logs summarizer-app" }

        Set-Status "Fertig — Summarizer läuft auf Port $port." 100
        Write-Log ""
        Write-Log "=== Installation abgeschlossen ==="
        Write-Log "Studio:      http://localhost:$port"
        Write-Log "Starten:     Desktop-Verknüpfung 'Summarizer' (startet Docker + Browser)"
        Write-Log "Benutzer:    admin"
        Write-Log "Passwort:    admin (Standard - bitte im Studio unter Benutzer ändern)"
        Write-Log "Login:       standardmäßig AUS (aktivierbar: Studio -> System -> Zugriff)"
        $openButton.Enabled = $true
        Start-Process "http://localhost:$port"
    } catch {
        Set-Status "Fehler: $_" 0
        Write-Log "FEHLER: $_"
        $installButton.Enabled = $true
    }
})

$openButton.Add_Click({ Start-Process "http://localhost:$([int]$portBox.Value)" })

[void]$form.ShowDialog()
