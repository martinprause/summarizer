# =====================================================================
#  Summarizer — grafischer Installations-Assistent (Windows)
#  Start ueber Setup-Summarizer.bat (Doppelklick).
# =====================================================================
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

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
$subtitle.Text = "Dein privates Wissensarchiv — laeuft komplett lokal"
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
[void]$langBox.Items.AddRange(@("Deutsch / gemischt", "Englisch"))
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

# Admin-Zugang fest: admin/admin - Passwort im Studio unter Benutzer aenderbar
New-Label "Port der Weboberflaeche" 28 158 250 $true | Out-Null
$portBox = New-Object System.Windows.Forms.NumericUpDown
$portBox.Location = New-Object System.Drawing.Point(28, 180)
$portBox.Size = New-Object System.Drawing.Size(120, 26)
$portBox.Minimum = 1024
$portBox.Maximum = 65535
$portBox.Value = 8181
$form.Controls.Add($portBox)

$audioBox = New-Object System.Windows.Forms.CheckBox
$audioBox.Text = "Audio-Transkription mitinstallieren (Whisper, ca. 2 GB)"
$audioBox.Location = New-Object System.Drawing.Point(28, 216)
$audioBox.Size = New-Object System.Drawing.Size(420, 24)
$audioBox.Font = New-Object System.Drawing.Font("Segoe UI", 9.5)
$form.Controls.Add($audioBox)

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
$openButton.Text = "Studio oeffnen"
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
        # 0. WSL 2 (Docker Desktop braucht es als Backend)
        Set-Status "Pruefe WSL 2 ..." 3
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
                "WSL 2 wurde installiert. Bitte Windows neu starten und diesen Assistenten erneut ausfuehren.",
                "Neustart noetig", "OK", "Information") | Out-Null
            $installButton.Enabled = $true
            return
        }
        Write-Log "WSL 2 bereit."

        # 1. Docker
        Set-Status "Pruefe Docker ..." 5
        if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
            Write-Log "Docker nicht gefunden — starte Installation via winget ..."
            Set-Status "Installiere Docker Desktop (dauert einige Minuten) ..." 10
            winget install -e --id Docker.DockerDesktop --accept-source-agreements --accept-package-agreements 2>&1 |
                ForEach-Object { Write-Log $_ }
            [System.Windows.Forms.MessageBox]::Show(
                "Docker Desktop wurde installiert. Bitte Windows neu starten, Docker einmal oeffnen und diesen Assistenten erneut ausfuehren.",
                "Neustart noetig", "OK", "Information") | Out-Null
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
            if ($LASTEXITCODE -ne 0) { throw "Docker-Engine laeuft nicht." }
        }
        Write-Log "Docker bereit."

        # 2. Port pruefen
        Set-Status "Pruefe Port ..." 18
        $port = [int]$portBox.Value
        while (-not (Test-PortFree $port)) {
            Write-Log "Port $port belegt — probiere $($port + 1)"
            $port++
        }
        $portBox.Value = $port
        Write-Log "Verwende Port $port."

        # 3. Konfiguration schreiben
        Set-Status "Schreibe Konfiguration ..." 24
        if ($langBox.SelectedIndex -eq 1) { $embed = "nomic-embed-text"; $dim = 768 }
        else { $embed = "bge-m3"; $dim = 1024 }
        $ram = [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB)
        $chat = if ($ram -ge 16) { "qwen3.5:9b" } else { "qwen3.5:4b" }
        Write-Log "RAM ${ram} GB -> Chat-Modell $chat, Embeddings $embed"
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
"@ | Out-File -Encoding utf8 (Join-Path $root ".env")

        # 4. Images laden
        $profiles = @("--profile", "app")
        if ($llmBox.SelectedIndex -eq 0) { $profiles += @("--profile", "local-llm") }
        if ($audioBox.Checked) { $profiles += @("--profile", "whisper") }

        Set-Status "Lade Programm-Images herunter ..." 35
        Write-Log "Ziehe fertige Images von Docker Hub (kein lokaler Build) ..."
        docker compose @profiles pull 2>&1 | ForEach-Object { Write-Log $_ }
        if ($LASTEXITCODE -ne 0) {
            throw "Images konnten nicht geladen werden - Internetverbindung pruefen."
        }

        Set-Status "Starte Container ..." 55
        docker compose @profiles up -d 2>&1 | ForEach-Object { Write-Log $_ }
        if ($LASTEXITCODE -ne 0) { throw "Container-Start fehlgeschlagen." }

        # 4b. Start-Verknuepfungen anlegen
        Set-Status "Erstelle Verknuepfungen ..." 65
        try {
            $shell = New-Object -ComObject WScript.Shell
            foreach ($dir in @([Environment]::GetFolderPath("Desktop"),
                               (Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs"))) {
                $lnk = $shell.CreateShortcut((Join-Path $dir "Summarizer.lnk"))
                $lnk.TargetPath = Join-Path $root "Summarizer-Start.bat"
                $lnk.WorkingDirectory = $root
                $lnk.IconLocation = "$env:SystemRoot\System32\shell32.dll,14"
                $lnk.Description = "Summarizer Studio starten"
                $lnk.Save()
            }
            Write-Log "Verknuepfungen auf Desktop und im Startmenue angelegt."
        } catch { Write-Log "Verknuepfung konnte nicht angelegt werden: $_" }

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
        if (-not $ready) { throw "Anwendung antwortet nicht — Logs pruefen: docker logs summarizer-app" }

        Set-Status "Fertig — Summarizer laeuft auf Port $port." 100
        Write-Log ""
        Write-Log "=== Installation abgeschlossen ==="
        Write-Log "Studio:      http://localhost:$port"
        Write-Log "Starten:     Desktop-Verknuepfung 'Summarizer' (startet Docker + Browser)"
        Write-Log "Benutzer:    admin"
        Write-Log "Passwort:    admin (Standard - bitte im Studio unter Benutzer aendern)"
        Write-Log "Login:       standardmaessig AUS (aktivierbar: Studio -> System -> Zugriff)"
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
