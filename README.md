# Android AR Viewer Sync

A greenfield **Android-only** framework that wires together three phone hardware capabilities — camera, 3-axis gyroscope, and touch — to drive a 3D model viewer in lock-step with the physical device pose.

The phone's actual camera direction (yaw/pitch) and roll are synced in real-time with the 3D viewer's virtual camera. As you rotate/tilt the phone, both the live camera feed and the 3D scene "look" in the same direction. This lets you visually verify the 3D camera motion by comparing it against the real camera feed (AR-style overlay).

## What's in the box

- **AR-style overlay layout**: full-screen live camera preview + transparent Three.js WebView on top
- **Real-time pose sync** via `TYPE_GAME_ROTATION_VECTOR` (no compass calibration needed)
- **Enclosed default model**: procedurally-built room (floor, ceiling, 4 walls, window with skybox, table, chairs, lamp, rug) — easy to perceive camera motion inside a constrained space
- **Custom model loading**: pick a `.glb` / `.gltf` file from device storage, or paste a URL
- **Smoothing**: native low-pass + deadzone on sensors, plus a second-stage lerp in JS — no jitter
- **Roll compensation**: ON by default (3D horizon stays level when you tilt the phone); toggle OFF to pass roll through
- **Landscape support**: manifest `configChanges` avoids Activity recreation; sensor matrix remapped per display rotation on every event
- **Pinch-to-zoom FOV**: 20°–110° clamp
- **HUD**: live yaw/pitch/roll (deg), FPS, current camera (front/back), roll-comp status, viewer ready state

## Tech stack

- Kotlin 1.9.24 + Jetpack Compose (BOM 2024.06)
- CameraX 1.3.4
- WebView (Chromium) hosting Three.js r160 (ES module build, vendored into `assets/viewer/`)
- Gradle 8.7 (Kotlin DSL), AGP 8.5.0
- minSdk 24, targetSdk 34

## Project structure (high-level)

```
AndroidARViewerSync/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/wrapper/{gradle-wrapper.jar, gradle-wrapper.properties}
├── gradlew.bat
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/viewer/
│       │   ├── index.html           # transparent canvas host + importmap
│       │   ├── viewer.js            # render loop + window.__setOrientation API
│       │   ├── RoomScene.js         # procedural room
│       │   ├── three.module.min.js  # vendored Three.js r160
│       │   └── GLTFLoader.module.js # vendored Three.js GLTFLoader
│       └── java/com/example/arviewersync/
│           ├── ArViewerApplication.kt
│           ├── MainActivity.kt
│           ├── ui/                  # Compose screens + HUD + control bar + theme
│           ├── camera/              # CameraController (CameraX), CameraPreviewView
│           ├── sensor/              # OrientationProvider, LowPassFilter, EulerUtil
│           ├── viewer/              # ThreeViewerWebView, ThreeBridge
│           └── viewmodel/           # ARViewerViewModel
```

## Data flow

```
Sensor (GAME_ROTATION_VECTOR) ~50 Hz
        │
        ▼
OrientationProvider
  - getRotationMatrixFromVector
  - remapCoordinateSystem (by display rotation → handles landscape)
  - getOrientation → [azimuth, pitch, roll] (rad)
  - LowPassFilter (α=0.25, deadzone 0.15°)
  - throttle ≤60 Hz
  - mirror yaw when front camera is active
        │
        ▼ StateFlow<OrientationEuler>
ARViewerViewModel (collects)
  - applies roll-compensation (zeroes roll when enabled)
  - pushes to ThreeBridge.pushOrientation
        │
        ▼ evaluateJavascript("window.__setOrientation(y,p,r,rc)")
Three.js viewer.js (60Hz coalesced)
  - second-stage lerp k=0.35 + JS deadzone
  - camera.rotation (Euler YXZ) = (-yaw, -pitch, rollCompOn ? 0 : -roll)
```

## Build & run

### Prerequisites

- **Android Studio** (Hedgehog 2023.1.1+ or newer recommended), with:
  - Android SDK 34
  - Android SDK Platform-Tools
  - Android SDK Build-Tools 34.x
  - JDK 17 (bundled with Android Studio)
- **A real Android phone** with USB debugging enabled. Sensors and camera do not work well in the Android Emulator (the emulator has no real gyroscope; it can simulate rotation but the values are coarse).

### Option A: Build in Android Studio

1. Open Android Studio → **File → Open** → select `D:/src/AndroidARViewerSync`.
2. Wait for Gradle sync to finish. (If asked to upgrade AGP, keep the current version.)
3. Connect your phone via USB, allow USB debugging on the phone when prompted.
4. Pick your phone in the device dropdown.
5. Click **Run ▶** (or `Shift+F10`).

### Option B: Build from command line

Make sure `ANDROID_HOME` is set (or create a `local.properties` with `sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk`).

```bash
# Windows PowerShell
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug   # pushes to a connected device
```

### On first launch

- The app will request **Camera** permission. Approve.
- The procedural room loads automatically; sensor data starts flowing on Activity resume.

## Controls

- **Flip** — switch between back and front camera. (Front-camera preview is mirrored for selfie intuition; the 3D camera yaw is also mirrored so the scene rotates consistently with what the mirrored preview shows.)
- **Roll** — toggle roll compensation. ON = 3D horizon stays level when you tilt the phone. OFF = full roll pass-through.
- **Reset** — re-center the 3D camera (yaw/pitch/roll = 0, FOV = 75°).
- **GLTF** — open a dialog: pick a `.glb` / `.gltf` file from storage, OR paste a URL. The room is replaced by the model; skybox stays.
- **FOV** — reset FOV to 75°.

Pinch anywhere on the screen to narrow/widen the 3D FOV (20°–110°).

## Verifying the sync (10-point checklist)

Run on a real phone and check:

1. **Project builds** — Android Studio → Build APK succeeds; or `gradlew assembleDebug`.
2. **Install** — `gradlew installDebug` after enabling USB debugging.
3. **Camera works** — full-screen live preview on launch; tap "Flip" → front/back switches.
4. **Sensor sync** — rotate phone left → both the live preview shows what's to the left AND the 3D room view rotates so you're now facing the room's left wall. Tilt up/down → 3D camera pitch matches. Hold still → 3D view stable (no jitter).
5. **Roll comp** — with roll comp ON, tilt phone clockwise (roll) → 3D horizon stays level. Toggle OFF → 3D view rotates with phone.
6. **Landscape** — rotate phone to landscape → layout adapts; sensor values are correctly remapped (no jump/spin).
7. **Pinch FOV** — pinch in/out → 3D FOV narrows/widens; HUD shows new FOV.
8. **Custom model** — tap "GLTF" → pick a `.glb` → room is replaced by the model; camera rotation still works.
9. **HUD** — live yaw/pitch/roll numbers update smoothly (no jitter); FPS ≥ 30.
10. **Background/battery** — press Home → sensors unregister (verify via `adb logcat | grep -i sensor`); no sensor drain.

## Tuning knobs

| File | Knob | Effect |
|---|---|---|
| `sensor/LowPassFilter.kt` | `alpha` (0.25) | Closer to 1 = snappier but more jitter; closer to 0 = smoother but laggier |
| `sensor/LowPassFilter.kt` | `deadzoneDeg` (0.15°) | Increase to suppress more micro-jitter when held still |
| `sensor/OrientationProvider.kt` | `minEmitIntervalNanos` (16 ms) | Lower = higher push rate (and CPU); raise to cap |
| `viewer/ThreeBridge.kt` | `FRAME_MS` (16L) | Coalescing window for orientation pushes |
| `assets/viewer/viewer.js` | `k` (0.35) | Second-stage lerp factor on the JS side |
| `assets/viewer/viewer.js` | `0.0003` | JS deadzone in radians (sub-pixel jitter guard) |
| `app/build.gradle.kts` | `minSdk` | Bumping to 26 gets adaptive icon support; to 29 guarantees ES modules |

## Known limitations (intentional — framework only)

- No ARCore / SLAM — device pose is purely from inertial sensors; absolute position drift over time is expected.
- iOS not supported (Android-only by design).
- No camera-frame recording.
- No multi-user sync.
- ES modules require Chromium WebView 80+, which covers Android 8+ and Android 7 via Play-updated WebView. If you must support a totally offline Android 7 device, swap `index.html` to load an older UMD r147 build of Three.js instead.

## License

This skeleton is provided as-is for your project. Replace with your own license before shipping.
