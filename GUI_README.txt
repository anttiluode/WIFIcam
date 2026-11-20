WIFIcam Desktop — wireless Android camera control on Windows
=============================================================

GET STARTED (Windows 10/11):
1. Download the repository ZIP from https://github.com/anttiluode/WIFIcam
   and extract it, or clone the repository.
2. Have Python 3.13 installed (the included .bat files use python3.13).
3. Double-click INSTALL_GUI.bat once to install Pillow for JPEG preview.
4. Double-click LAUNCH_GUI.bat to open the desktop camera controller.
5. On your Android phone, install WIFIcam (app-debug.apk in the repo).
6. Connect phone and PC to the same Wi-Fi, open WIFIcam on the phone,
   and keep it awake with WIFIcam in the foreground.
7. Tap Copy control URL on the phone, paste the COMPLETE http:// URL
   including ?token=... into the Windows GUI, and click Connect.

Download Android APK:
https://raw.githubusercontent.com/anttiluode/WIFIcam/main/app-debug.apk

Features:
- Live preview from /snapshot with display-only rotation
- Selection of available logical/physical Camera2 cameras
- Supported zoom range and quick 0.5x, 1x, 2x, 3x, 5x controls
- Manual focus/autofocus, focus-distance slider and presets
- ISO, shutter time (microseconds) and auto-exposure lock
- White balance modes and lock, torch/LED when supported
- Preview JPEG quality, full-resolution still photos and burst captures
- Save-folder picker and camera capability information

Alternatively, paste the same URL into a Windows web browser to use
the built-in control panel without the Python desktop GUI.

The desktop GUI needs Python 3.13 and Pillow; it is not a Windows EXE.
The phone must remain awake with WIFIcam in the foreground.
Manual settings, lenses and zoom availability depend on Android Camera2
support and the phone maker's camera firmware.
Focus 0 diopters is infinity; 2 D is nominally 50 cm.
The /api/set response means the setting was queued, not independently
verified as applied by the hardware.
The GUI stores the URL at %APPDATA%\WIFIcam\desktop.json.
Keep that URL and its token private. Do not expose port 8765 to the internet.
