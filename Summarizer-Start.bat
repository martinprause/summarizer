@echo off
REM Summarizer starten (Doppelklick) — startet Container und oeffnet den Browser
cd /d "%~dp0"
start "" powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%~dp0Summarizer-Start.ps1"
