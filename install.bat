@echo off
REM Summarizer Installer — Doppelklick-Start fuer Windows.
REM Startet das eigentliche PowerShell-Skript mit passenden Rechten.
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1"
pause
