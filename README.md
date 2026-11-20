# WIFIcam — turn an Android phone into a controllable scientific camera

![webcam](wecbcam.png)

A **native Android Camera2** camera server. Open a browser on a Windows computer and control the phone's cameras over local Wi-Fi. Designed for Galaxy S20 optical experiments, but supports Android 9+ devices which expose Camera2.

**No root, cloud account, subscription, extra web service, or external Android libraries.** All data stays on your local network unless you explicitly save or share it elsewhere.

![phone](phone.jpg)

## What works in this first version

- Discover actual public **logical camera IDs** and physical child IDs reported by the phone, including focal length metadata. Select a public camera; attempt a physical child via `0@2`, etc. Physical stream selection uses `OutputConfiguration.setPhysicalCameraId` and may be blocked by Samsung firmware or stream-combination restrictions. It will report the failure rather than pretending to switch lenses.
- Browser-based **live MJPEG feed**, normally up to ~10 frames/sec, selectable 640 / 1280 / 1920 width ceilings. Actual resolution depends on the sensor's advertised modes; 1920 may be CPU intensive.
- **Full-resolution still JPEGs** on `/photo` (largest supported JPEG size <= 16 MP if available), independent of preview quality.
- Manual ISO, exposure time in microseconds, focus distance in diopters, focus/exposure automation, AE/AWB locks, available white-balance presets, torch and zoom; on Android 11+ where exposed, the logical camera uses `CONTROL_ZOOM_RATIO` for possible ultrawide/tele lens switching, with crop-based fallback on older devices.
- Local HTTP API and zero-dependency `pc_client.py` for repeated optical calibration captures.
- **Persistent random 160-bit access token**, generated once and stored in private Android app preferences; required for every HTTP endpoint. It survives activity restarts and app updates, and resets only when app storage is cleared or the app is uninstalled.

**Caveats:** Not all Android cameras offer MANUAL_SENSOR; the UI tells you whether it is advertised. Physical child cameras are not guaranteed to be individually usable, and some selections may fail. Manual focus/ISO/exposure are requested through Camera2; actual applied metadata has not yet been verified on an S20. JPEG photos are sensor-native orientation. This app must remain **open in foreground with the screen on**; it does not operate as a background service. The stream is MJPEG over HTTP, not lossless raw, H.264, or video recording.

## Windows desktop control panel (NEW)

You can use the browser dashboard by opening the complete phone URL, **or** run the dedicated Tkinter GUI on Windows 10/11, with a large live preview, camera/lens dropdown, 0.5×/1×/2×/3×/5× quick zoom, manual ISO/shutter, focus slider, white balance, torch, full-resolution JPEG and burst controls.

1. Download or clone this repository and open the `WIFIcam` folder on Windows.
2. Double-click **`INSTALL_GUI.bat`** once (installs Pillow for JPEG preview).
3. Double-click **`LAUNCH_GUI.bat`** to open the desktop window.
4. Paste the complete URL shown on the S20 (including `?token=...`) into the top field, click **Connect**. The PC GUI remembers it in `%APPDATA%\\WIFIcam\\desktop.json` (keep this private).

You do not need a new APK for the desktop GUI: it uses the current phone server and `/snapshot`, `/photo`, and `/api/set` endpoints. The preview is polled JPEG snapshots, not a raw stream. Some zoom or lens choices may be digital or unavailable depending on Camera2 and Samsung firmware. See `GUI_README.txt` for more details. No other Python dependencies beyond Pillow.

## Build / install

### Easiest: GitHub Actions APK

1. Open the repository **Actions** tab → **Build Android APK** → select the latest successful run (or **Run workflow**).
2. Download the `WIFIcam-debug-apk` artifact ZIP and extract `app-debug.apk`.
3. Transfer the APK to your S20. Allow installation from the file manager/source you used, install WIFIcam, and grant Camera permission.
4. Connect the phone and PC to the **same Wi-Fi**, and keep WIFIcam open on the phone.
5. The phone displays a URL such as `http://192.168.1.157:8765/?token=...`. Open **the exact URL** in your PC browser. Port `8765` is **not** IP Webcam's `8080`. The token is stable across app restarts, but the Wi-Fi IP address can still change.

The workflow builds a **debug APK**, not a signed Play Store release. GitHub Actions must be enabled for the repository.

### Android Studio

Open this repository as an Android Studio project. It uses Android Gradle Plugin 8.7.3, Gradle 8.9, Java 17 and Android SDK 35, all available through Android Studio / the GitHub workflow. It has no Gradle wrapper JAR, so use an installed Gradle 8.9 or configure Android Studio to use one. `gradle assembleDebug` builds the APK.

## Python control on Windows

Copy the complete URL shown on the phone (including token):

```powershell
$cam = 'http://192.168.1.157:8765/?token=PASTE_PHONE_TOKEN_HERE'
python3.13 pc_client.py $cam cameras
python3.13 pc_client.py $cam state
python3.13 pc_client.py $cam set --manual --iso 400 --shutter-us 10000 --manual-focus --focus 0
python3.13 pc_client.py $cam photo --output calibration_0001.jpg
python3.13 pc_client.py $cam burst --count 20 --interval 1 --dir runs\camera
```

**Important:** manual focus `0` means infinity, not close-up. In the phone's Camera2 convention, increasing diopters focuses closer. For a phone observing a monitor up close, experiment with a nonzero diopter value.

Use the exposed camera IDs, not a guessed Samsung camera name:

```powershell
python3.13 pc_client.py $cam camera 0 --width 1280
python3.13 pc_client.py $cam camera '0@2' --width 640
```

After switching lenses, wait for `/api/state` to report `"status": "streaming"`. On Android 11+ with a supported zoom-ratio range, try 0.5x / 1x / 3x through the logical camera to request ultrawide / wide / tele behavior. A logical camera may automatically swap lenses internally under different zoom/light conditions; choosing a reported *physical* child attempts to avoid that, but requires hardware support.

## HTTP endpoints

All endpoints require `?token=PHONE_TOKEN` (for POST too):

| Method | Path | Result |
| --- | --- | --- |
| GET | `/` | Dark browser dashboard |
| GET | `/api/cameras` | Logical and physical camera IDs, capabilities/ranges |
| GET | `/api/state` | Current settings, dimensions, error/status and frame counts |
| POST | `/api/camera?id=0&width=1280` | Open camera; `width=640/1280/1920` |
| POST | `/api/set?manualExposure=true&iso=400&shutterUs=10000` | Update requested settings |
| GET | `/stream` | Multipart MJPEG stream |
| GET | `/snapshot` | Latest preview JPEG |
| GET | `/photo` | New high-resolution JPEG photo |

Additional `/api/set` keys: `manualFocus`, `focus` (diopters), `zoom`, `quality` (preview JPEG 30..100), `awbMode` (Camera2 integer), `aeLock`, `awbLock`, `torch`. Response `accepted=true` means queued; verify subsequent `/api/state` and real captures. MJPEG quality is independent of still JPEG quality (95).

## LightScan and reproducibility

For a repeatable scanning session:

1. Put the phone/monitor in a stable mount, prevent screen movement, darken the room.
2. Check `/api/cameras` and choose a camera. A physical lens can improve repeatability **only if it is actually supported**.
3. Try fixed focus and manual exposure + ISO. Lock white balance or choose a supported fixed mode.
4. Record one frame from `/photo` per projected light pattern, and associate the filenames with pattern IDs and timestamps. Do not assume the JPEG represents a lossless/linear sensor reading; for linear photometry, RAW/DNG support would be a separate project.
5. Validate capture geometry, saturation and linearity with controls before attempting inversion through the diffuser.

`LightScan` can consume the HTTP JPEG bytes from `/photo` directly; unlike IP Webcam, this endpoint requests a *new still image* from Camera2 rather than a browser-cropped MJPEG frame.

## Security

This is a **local lab instrument**, not an internet-facing service. Keep the phone on a trusted private LAN. Access is protected by a random token, but transport uses **unencrypted HTTP**, so other devices able to intercept LAN traffic could read it. Do **not** port-forward `8765`, expose it to the internet, or paste the token into public logs/issues.

## Known limitations / next experiments

- Compile/runtime validation on an actual Galaxy S20 remains to be done; vendor Camera2 behavior varies.
- Future version: capture-result metadata (measured exposure, ISO, focus, focal length), DNG/RAW where available, hardware-consistent frame timestamps, per-sensor physical still-session fallbacks, and per-channel linear calibration for LightScan.
- The stream requires the phone to stay awake. Turning off the screen may suspend the camera or server.

MIT licensed.
