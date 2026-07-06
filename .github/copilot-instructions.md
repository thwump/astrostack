# Copilot Instructions

## Project Overview

**AstroStack** — An Android astrophotography camera app that captures RAW sensor frames and stacks them to reveal deep-sky features (nebulae, galaxies, star clusters). Uses Camera2 API for full RAW/DNG capture with manual exposure control, then aligns and stacks frames using signal-to-noise ratio enhancing algorithms.

## Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose + Material 3
- **Camera:** Camera2 API (RAW_SENSOR capture, DngCreator) + CameraX for preview
- **Image Processing:** Custom Kotlin stacking algorithms (mean, median, sigma-clipping, kappa-sigma); OpenCV Android for star alignment
- **Architecture:** MVVM + Clean Architecture
- **DI:** Hilt
- **Database:** Room (capture session metadata)
- **Async:** Kotlin Coroutines + Flow
- **Image Loading:** Coil
- **Navigation:** Jetpack Compose Navigation
- **Min SDK:** 26 (Android 8.0) | Target SDK: 35

## Architecture

```
app/src/main/java/com/astrostack/app/
├── AstroStackApp.kt          # @HiltAndroidApp Application class
├── MainActivity.kt           # Single activity host
├── Navigation.kt             # NavHost + routes
├── camera/
│   ├── CameraState.kt        # State data classes & settings
│   ├── RawCameraManager.kt   # Camera2 RAW capture, DNG saving
│   └── CaptureController.kt  # High-level capture session controller
├── stacking/
│   ├── StackingAlgorithm.kt  # Algorithm enum & config
│   ├── ImageStacker.kt       # Mean/Median/Sigma-clipping stacking
│   ├── StarAligner.kt        # Star detection + translational alignment
│   └── HistogramStretch.kt   # Levels/curves post-processing
├── data/
│   ├── CaptureSession.kt     # Room @Entity for session metadata
│   ├── AppDatabase.kt        # Room database
│   └── ImageRepository.kt   # Repository (Room + filesystem)
├── viewmodel/
│   ├── CameraViewModel.kt    # Exposure settings, capture state
│   └── StackingViewModel.kt  # Stacking progress and results
└── ui/
    ├── theme/
    │   ├── Color.kt          # Deep-red palette (preserves night vision)
    │   └── Theme.kt          # Dark Material 3 theme
    ├── CameraScreen.kt       # Live preview + manual controls
    ├── StackingScreen.kt     # Stack controls + progress
    └── GalleryScreen.kt      # Browse stacked results
```

## Development Guidelines

- Use **Coroutines** (`Dispatchers.IO` for disk/camera, `Dispatchers.Default` for stacking math)
- Camera2 RAW capture must check `REQUEST_AVAILABLE_CAPABILITIES_RAW` before use
- Stacking operates on **float arrays** in linear light (not gamma-encoded values)
- Always disable noise reduction, shading correction, and hot-pixel correction in capture requests — stacking handles this
- Set `LENS_FOCUS_DISTANCE = 0f` (infinity) for all astrophotography captures
- UI theme is **dark red** — astronomers use red light to preserve night dark-adaptation
- Image stacking is tiled to avoid OOM on large sensors (process in horizontal strips)
- RAW files are saved as `.dng` to internal app storage; stacked results as `.tiff` or `.png`
- Use `largeHeap = true` in manifest; still be defensive with memory

## Common Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Run unit tests
./gradlew test

# Run instrumented tests (requires connected device)
./gradlew connectedAndroidTest

# Install on connected device
./gradlew installDebug

# Generate Gradle wrapper (first time)
gradle wrapper --gradle-version 8.8
```

## Notes

- Test RAW capture on physical device only — emulators do not support Camera2 RAW
- Use `adb logcat -s AstroStack` to filter app logs
- For star alignment testing, use the sample DNG files in `app/src/test/assets/`
- Sigma-clipping default κ = 2.0 works well for most skies; increase to 3.0 for bright skies
- Long exposures (>30s) may require `CONTROL_AE_ANTIBANDING_MODE_OFF`
