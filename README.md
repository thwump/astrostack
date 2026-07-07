# AstroStack 🔭

An Android astrophotography camera app that captures RAW sensor frames and stacks them to reveal deep-sky features — nebulae, galaxies, star clusters, and more.

## Features

- **RAW / DNG capture** via Camera2 API with full manual control
  - ISO 100 – device maximum
  - Shutter speeds from 1 s to 30 s
  - Hyperfocal lock (**Infinity Focus lock 🌌**) & continuous focus (**Auto Focus 🔍**) toggle
  - OIS disabled (use a tracking mount or fixed tripod)
  - All on-device noise reduction disabled — stacking handles it
- **Continuous Stacking & Indefinite Capture**:
  - Captures indefinitely until manually stopped or cancelled.
  - **Save All RAW Frames Toggle**: Deletes RAW DNG files automatically from local storage after real-time stacking to conserve space (saving ~25MB per frame) when disabled.
  - **Auto Stack Toggle**: Performs real-time aligned stacking in the background while updating viewfinder counts.
- **Drift Alignment & Quality Rejection**:
  - Aligns frames dynamically in real-time, matching star centroids.
  - Collapsible **Stacking Settings ⚙** panel allows users to configure Drift Handling (None/Crop/Mosaic), minimum star count rejection limit, and star detection sensitivity (20-255 threshold) before capture starts.
- **Streamlined Results Screen**:
  - Bypasses post-capture parameters setup once live stacking completes.
  - Instantly displays the finalized stacked image, runs Astrometry.net Plate Solving (with catalog star labels), and exports results.
- **High-Resolution Astronomical Exporting**:
  - Export stacked images as native 16-bit **TIFF** or standard astronomical **FITS** (Flexible Image Transport System) directly to the phone's public `Pictures/AstroStack/` gallery.
- **Immersive Fullscreen View ⛶**:
  - Toggles a clean, distraction-free viewfinder preview (fading all top/bottom controls away) so you can watch stacked astro-images improve cleanly over time.
- **Real-Time Alignment Diagnostics**:
  - Outputs real-time logcat records (`adb logcat | grep AstroStack`) and writes a localized `alignment_diagnostics.txt` file to the session captures folder on disk. Shows detected star count, pixel translation offsets (dx, dy), and match quality percentage.
- **Frame stacking algorithms**:
  - Mean — fastest, best SNR
  - Median — immune to satellite trails
  - Sigma Clipping — best all-round (κ configurable, default 2.0)
  - Winsorized Sigma — clipping with boundary replacement
  - Maximum — star trails / comets
- **Histogram stretch** — automatic midtone stretch (AutoSTF-like) to reveal faint nebulosity
- **Dark red UI** — preserves night dark adaptation at the telescope
- **Gallery** — browse and manage all capture sessions

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Camera | Camera2 API (RAW_SENSOR + DngCreator) + CameraX (preview) |
| Image processing | Custom Kotlin algorithms; OpenCV Android (alignment) |
| Architecture | MVVM + Clean Architecture |
| DI | Hilt |
| Database | Room |
| Async | Kotlin Coroutines + Flow |
| Image loading | Coil |
| Min SDK | 26 (Android 8.0) |

## Project Structure

```
app/src/main/java/com/astrostack/app/
├── AstroStackApp.kt          Hilt application class
├── MainActivity.kt           Single Compose activity
├── Navigation.kt             NavHost + route definitions
├── camera/
│   ├── CameraState.kt        Settings + state data classes
│   ├── RawCameraManager.kt   Camera2 RAW capture core
│   └── CaptureController.kt  Multi-frame session orchestrator
├── stacking/
│   ├── StackingAlgorithm.kt  Algorithm enum + StackingConfig
│   ├── ImageStacker.kt       Tiled stacking engine
│   ├── StarAligner.kt        Star detection + translation alignment
│   └── HistogramStretch.kt   AutoSTF + manual curves
├── data/
│   ├── CaptureSession.kt     Room entities + DAO
│   ├── AppDatabase.kt        Room database
│   └── ImageRepository.kt   Repository (DB + filesystem)
├── di/
│   └── DatabaseModule.kt     Hilt module
├── viewmodel/
│   ├── CameraViewModel.kt   Camera UI state
│   └── StackingViewModel.kt  Stacking progress + results
└── ui/
    ├── theme/
    │   ├── Color.kt           Deep-red night palette
    │   └── Theme.kt           Dark Material 3 theme
    ├── CameraScreen.kt        Live preview + capture controls
    ├── StackingScreen.kt      Algorithm config + progress + result
    └── GalleryScreen.kt       Session browser
```

## Building

> **Prerequisites:** Android Studio Hedgehog or later, JDK 17, Android SDK 35.

```bash
# Generate Gradle wrapper (first time only)
gradle wrapper --gradle-version 8.8

# Build debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Run unit tests
./gradlew test

# Run instrumented tests (requires connected device)
./gradlew connectedAndroidTest
```

## Device Requirements

- Android 8.0+ (API 26+)
- Rear camera with **RAW_SENSOR** capability  
  (check: `REQUEST_AVAILABLE_CAPABILITIES_RAW`)
- ≥ 4 GB RAM recommended for full-resolution stacking
- A motorised tracking mount or fixed tripod is strongly recommended

> **Note:** RAW capture cannot be tested on emulators. Use a physical device.

## Useful ADB Commands

```bash
# Filter AstroStack log output
adb logcat -s AstroStack

# Pull a captured DNG frame for desktop inspection
adb shell run-as com.astrostack.app ls files/captures/
adb pull /data/data/com.astrostack.app/files/captures/session_<ts>/frame_001.dng
```

## Sigma-Clipping Guidance

| Sky condition | Recommended κ |
|---|---|
| Dark rural sky | 2.0 (default) |
| Suburban / light polluted | 2.5 |
| Bright urban sky | 3.0 |
| Very few frames (< 5) | 3.0 – 4.0 |

## License

MIT
