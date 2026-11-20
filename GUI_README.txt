WIFIcam Desktop — Galaxy S20 camera control on Windows
=====================================================

FIRST TIME (Windows 10/11):
1. Download or clone this repository to e.g. E:\DocsHouse\1068\WIFIcam.
2. Double-click INSTALL_GUI.bat once to install Pillow (JPEG preview).
3. Double-click LAUNCH_GUI.bat.
4. Open WIFIcam on the S20, keep the phone awake and on the same Wi-Fi.
5. Paste its COMPLETE URL, including ?token=..., into the top field.
6. Click Connect. The URL is saved for subsequent launches in your
   user profile at %APPDATA%\WIFIcam\desktop.json.

Features:
- Live preview from /snapshot, display-only rotation
- Enumerate logical/physical Camera2 cameras
- Hardware-available zoom limits and quick 0.5x, 1x, 2x, 3x, 5x controls
- Manual and autofocus, focus slider and metric-distance presets
- ISO, exposure time (microseconds) and AE lock
- White balance mode and lock, torch
- Preview JPEG quality, full-resolution still JPEG, burst captures
- Save-folder picker and reported sensor capabilities

This PC GUI talks to the existing phone app. NO NEW APK is required.
The smartphone must remain awake with WIFIcam in the foreground.
Available lenses and manual controls depend on Samsung Camera2 firmware.
Focus 0 diopters is infinity; 2 D is nominally 50 cm.
The Camera2 /api/set response means queued, not independently measured.
The saved URL contains your access token: keep the config file private
and never expose port 8765 to the internet.
