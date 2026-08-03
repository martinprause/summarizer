@echo off
REM Summarizer — grafischer Installations-Assistent (Doppelklick)
cd /d "%~dp0"
start "" powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%~dp0Setup-Summarizer.ps1"
