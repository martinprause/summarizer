# =====================================================================
#  Summarizer starten — Doppelklick-Skript
#  Startet Docker (falls noetig), die Container und oeffnet den Browser.
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

# Laeuft es schon? Dann direkt oeffnen.
try {
    $r = Invoke-WebRequest "$url/login" -TimeoutSec 2 -UseBasicParsing
    if ($r.StatusCode -eq 200) { Start-Process $url; exit 0 }
} catch { }

# Tray-Hinweis waehrend des Starts
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

docker compose @profiles up -d *>$null

# Auf die App warten, dann Browser oeffnen
for ($i = 0; $i -lt 90; $i++) {
    try {
        $r = Invoke-WebRequest "$url/login" -TimeoutSec 2 -UseBasicParsing
        if ($r.StatusCode -eq 200) {
            $notify.ShowBalloonTip(3000, "Summarizer", "Bereit — Browser wird geoeffnet.", "Info")
            Start-Process $url
            Start-Sleep -Seconds 3
            $notify.Dispose()
            exit 0
        }
    } catch { }
    Start-Sleep -Seconds 2
}

[System.Windows.Forms.MessageBox]::Show(
    "Summarizer antwortet nicht.`n`nLogs pruefen:  docker logs summarizer-app",
    "Summarizer", "OK", "Warning") | Out-Null
$notify.Dispose()
