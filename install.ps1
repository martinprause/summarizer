# Summarizer Installer (Windows)
# Prueft/installiert Docker Desktop, fragt Konfiguration ab, startet den Stack.
$ErrorActionPreference = "Stop"
Write-Host "=== Summarizer Installation ===" -ForegroundColor Cyan

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

    $adminPw = Read-Host "Admin-Passwort (leer = wird generiert und geloggt)"

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
Write-Host "Admin-Login:  admin / (ADMIN_PASSWORD aus .env oder: docker logs summarizer-app | Select-String 'Passwort')"
if ($ready) {
    Start-Process "http://localhost:$port"
} else {
    Write-Host "App braucht noch einen Moment - Seite gleich manuell oeffnen: http://localhost:$port" -ForegroundColor Yellow
}
