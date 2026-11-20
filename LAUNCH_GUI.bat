@echo off
cd /d "%~dp0"
python3.13 pc_gui.py
if errorlevel 1 pause
