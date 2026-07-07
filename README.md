# AstroStack 🔭

An Android astrophotography camera app that captures RAW sensor frames and stacks them to reveal deep-sky features — nebulae, galaxies, star clusters, and more.

Designed to emulate a tracking mount using a fixed tripod by leveraging high-performance software alignment, drift correction, real-time stacking, and advanced calibration workflows.

---

## Features

- **RAW / DNG Capture** via Camera2 API with full manual control
  - ISO 100 – device hardware maximum
  - Shutter speeds from 1 s to 30 s
  - Hyperfocal lock (**Infinity Focus lock 🌌**) and continuous focus (**Auto Focus 🔍**) toggle
  - Optical Image Stabilization (OIS) disabled defensively for tripod use
  - All on-device hardware noise reduction disabled — letting custom stacking algorithms handle it cleanly
- **Advanced Real-Time Calibration**
  - **Master Dark Calibration**: Captures and averages 5 frames with the lens covered to isolate and subtract thermal sensor noise and hot pixels.
  - **Master Flat Calibration**: Captures and averages 10 frames against a flat light source to correct for optical vignetting (dark corners) and lens dust.
  - **Cosmetic Hot Pixel Correction**: Runs a real-time 3x3 median filter check on incoming frames to eliminate hot pixels before alignment.
- **Drift Alignment & Quality Rejection**
  - Aligns frames dynamically in real-time, matching star centroids using rigid translation and rotation alignment.
  - **FWHM Sharpness Rejection**: Measures the Full Width at Half Maximum (FWHM) of stars in real-time. Auto-rejects frames affected by wind or tripod vibration if their FWHM is $> 1.4\times$ the reference frame's FWHM.
- **Light Pollution Gradient Removal**
  - Divides the frame into an $8 \times 8$ grid of sample tiles, measures median background brightness (ignoring stars), and subtracts a bilinearly interpolated light pollution gradient surface to eliminate city skyglow.
- **Advanced Stretch Modes**
  - **Histogram (STF)**: Auto-stretch optimized to reveal faint nebulosity and galaxies.
  - **Arcsinh (Color-Preserving)**: Stretches faint details based on the inverse hyperbolic sine (`asinh`) function while preserving correct star color ratios (chrominance) to prevent stars from burning white.
- **Quick Scout Frame 🔭**
  - Single-click 1.5s high-sensitivity exposure that is instantly stretched to verify target framing before starting a long capture sequence.
- **Streamlined Results Screen**
  - Bypasses post-capture parameters setup once live stacking completes.
  - Instantly displays the finalized stacked image, runs Astrometry.net Plate Solving (with catalog star labels), and exports results.
- **High-Resolution Astronomical Exporting**
  - Export stacked images as native 16-bit **TIFF** or standard astronomical **FITS** (Flexible Image Transport System) directly to the phone's public `Pictures/AstroStack/` gallery.
- **Immersive Fullscreen View ⛶**
  - Toggles a clean, distraction-free viewfinder preview (fading all top/bottom controls away) so you can watch stacked astro-images improve cleanly over time.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Camera | Camera2 API (RAW_SENSOR + DngCreator + SurfaceView preview) |
| Image processing | Custom Kotlin algorithms; OpenCV Android (alignment) |
| Architecture | MVVM + Clean Architecture |
| DI | Hilt |
| Database | Room |
| Async | Kotlin Coroutines + Flow |
| Image loading | Coil |
| Min SDK | 26 (Android 8.0) |

---

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
│   ├── GradientRemoval.kt    Tiled bilinear gradient removal
│   ├── ImageStacker.kt       Tiled stacking engine
│   ├── StarAligner.kt        Star detection + translation alignment
│   └── HistogramStretch.kt   AutoSTF + Arcsinh curves
├── data/
│   ├── CaptureSession.kt     Room entities + DAO
│   ├── AppDatabase.kt        Room database
│   └── ImageRepository.kt    Repository (DB + filesystem)
├── di/
│   └── DatabaseModule.kt     Hilt module
├── viewmodel/
│   ├── CameraViewModel.kt    Camera UI state
│   └── StackingViewModel.kt  Stacking progress + results
└── ui/
    ├── theme/
    │   ├── Color.kt          Deep-red night palette
    │   └── Theme.kt          Dark Material 3 theme
    ├── CameraScreen.kt       Live preview + scrollable settings + capture controls
    ├── StackingScreen.kt     Algorithm config + progress + result
    └── GalleryScreen.kt      Session browser
```

---

## Building

> **Prerequisites:** Android Studio Hedgehog or later, JDK 17, Android SDK 35.

```bash
# Generate Gradle wrapper (first time only)
# gradle wrapper --gradle-version 8.8

# Build debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Run unit tests
./gradlew test

# Run instrumented tests (requires connected device)
./gradlew connectedAndroidTest
```

---

## Device Requirements

- Android 8.0+ (API 26+)
- Rear camera with **RAW_SENSOR** capability  
  (check: `REQUEST_AVAILABLE_CAPABILITIES_RAW`)
- ≥ 4 GB RAM recommended for full-resolution stacking
- A fixed tripod or tracking mount is strongly recommended.
- **Note:** RAW capture cannot be tested on emulators. Use a physical device.

---

## Useful ADB Commands

```bash
# Filter AstroStack log output
adb logcat -s AstroStack

# Pull a captured DNG frame for desktop inspection
adb shell run-as com.astrostack.app ls files/captures/
adb pull /data/data/com.astrostack.app/files/captures/session_<ts>/frame_001.dng
```

---

## Sigma-Clipping Guidance

| Sky condition | Recommended κ |
|---|---|
| Dark rural sky | 2.0 (default) |
| Suburban / light polluted | 2.5 |
| Bright urban sky | 3.0 |
| Very few frames (< 5) | 3.0 – 4.0 |

---

## License

MIT
