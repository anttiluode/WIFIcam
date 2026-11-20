@echo off
cd /d "%~dp0"
python3.13 -m pip install -r requirements-gui.txt
if errorlevel 1 (
  echo Install failed. Try: py -3.13 -m pip install pillow
)
pause
