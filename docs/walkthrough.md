# AstroStack Feature Walkthrough

This walkthrough outlines all the major enhancements integrated into AstroStack to enable tripod astrophotography, star alignment, image processing, plate solving, and multi-format exporting.

---

## 🌌 Accomplishments & Features Added

### 1. Continuous Indefinite Capture Loop
- Replaced the fixed-frame cap with an **indefinite capture loop** that runs continuously until the user command stops or cancels it.
- **Controls**: Introduces an active **Stop & Save** button and a **Cancel** (abort without compiling) button on the viewfinder screen.
- **Location**: [`CaptureController.kt`](file:///Users/rob/.gemini/antigravity/scratch/astrostack/app/src/main/java/com/astrostack/app/camera/CaptureController.kt)

### 2. Live Statistics Overlay
- Updates viewfinder state reactively to present real-time progress statistics:
  - **CAPTURED**: Total sub-frames taken.
  - **STACKED**: Frames successfully aligned and integrated.
  - **REJECTED**: Frames discarded (e.g. high cloud, out-of-focus, or bumped tripod) due to falling below the minimum star limit.
- **Location**: [`CameraScreen.kt`](file:///Users/rob/.gemini/antigravity/scratch/astrostack/app/src/main/java/com/astrostack/app/ui/CameraScreen.kt) & [`CameraState.kt`](file:///Users/rob/.gemini/antigravity/scratch/astrostack/app/src/main/java/com/astrostack/app/camera/CameraState.kt)

### 3. Dynamic Local Saving & Storage Protection
- **Save All RAW Frames Toggle**: 
  - If **Disabled** (default): Deletes RAW DNG files from storage immediately after live alignment, preserving massive amounts of disk space (each RAW is ~25MB).
  - If **Enabled**: Keeps all sub-frames in the session captures folder for future offline analysis or high-resolution stacking.
- **Auto Stack Photos Toggle**: Enables or disables the live viewfinder stacking process on the fly.
- **Location**: [`CameraScreen.kt`](file:///Users/rob/.gemini/antigravity/scratch/astrostack/app/src/main/java/com/astrostack/app/ui/CameraScreen.kt) & [`CaptureController.kt`](file:///Users/rob/.gemini/antigravity/scratch/astrostack/app/src/main/java/com/astrostack/app/camera/CaptureController.kt)

### 4. Smart Stop-to-Save Compiler
- When tapping **Stop & Save**:
  - If *Save All* was enabled: Compiles a premium, full-resolution stacked image directly from all saved RAW frames using offline algorithms.
  - If *Save All* was disabled: Instantly saves the accumulated, stretched live-viewfinder preview from memory, ensuring zero delay.
- **Location**: [`CaptureController.kt`](file:///Users/rob/.gemini/antigravity/scratch/astrostack/app/src/main/java/com/astrostack/app/camera/CaptureController.kt)

### 5. Autofocus Toggle
- Standardized focus options:
  - **Infinity Focus 🌌**: Astro-photography default (LENS_FOCUS_DISTANCE = 0f).
  - **Auto Focus 🔍**: Enables continuous picture autofocus (useful for daytime testing in office).

### 6. Aspect Ratio & Distortion Fix
- Modified the viewfinder `AndroidView` to use `Modifier.requiredSize(...)` instead of `Modifier.size(...)`. This overrides Compose constraint clamping, ensuring the sensor's portrait/landscape bounds are drawn with perfect center-crop alignment without vertical stretching or distortion.

### 7. Tripod Exposure Calculator
- Suggests maximum exposure bounds via both the classic **500 Rule** and the sensor-specific **NPF Rule**.
- **Location**: [`ExposureCalculator.kt`](file:///Users/rob/.gemini/antigravity/scratch/astrostack/app/src/main/java/com/astrostack/app/camera/ExposureCalculator.kt)

### 8. FITS and TIFF Writers
- Developed native image writers to export stacked deep-sky images in standard astronomical and publishing formats directly from raw pixel buffers.
- **Location**: [`FitsWriter.kt`](file:///Users/rob/.gemini/antigravity/scratch/astrostack/app/src/main/java/com/astrostack/app/stacking/FitsWriter.kt) & [`TiffWriter.kt`](file:///Users/rob/.gemini/antigravity/scratch/astrostack/app/src/main/java/com/astrostack/app/stacking/TiffWriter.kt)

### 9. Astrometry.net API Plate Solver Client
- Handles HTTP multipart uploads, jobs status polling, and object annotation extraction from Astrometry.net servers, with an offline demo fallback.
- **Location**: [`AstrometryNetClient.kt`](file:///Users/rob/.gemini/antigravity/scratch/astrostack/app/src/main/java/com/astrostack/app/stacking/AstrometryNetClient.kt)

### 10. Clean Fullscreen View Toggle
- Integrated a fullscreen mode that hides all top header elements, preset selection menus, toggles, capture stats, and buttons.
- In regular mode, a **Fullscreen ⛶** icon button appears next to the Gallery button in the Top Bar.
- In fullscreen mode, all controls fade away, presenting a pure view of the live preview (including the stacked overlay as it updates). A subtle semi-transparent **Exit ⛶** floating button remains in the top-right corner to return to regular control view.
- **Location**: [`CameraScreen.kt`](file:///Users/rob/.gemini/antigravity/scratch/astrostack/app/src/main/java/com/astrostack/app/ui/CameraScreen.kt)

### 11. Stacking Settings Pre-Configuration (Settings Button ⚙)
- Added an expandable **Stacking Settings ⚙** panel directly below the "Auto Stack Photos" switch on the main camera screen.
- Allows users to configure all stacking parameters **before** capture:
  - **Drift Handling**: Choose None, Crop, or Mosaic alignment mode.
  - **Min Stars for Stacking**: Set the minimum stars (e.g. 3 to 15, default 5) required to integrate a frame, filtering out cloud-obscured or blurry images.
  - **Star Sensitivity Threshold**: Adjust star detection sensitivity via a slider (from 20 to 255). Lowering the threshold (e.g., to 40-70) makes detection much more sensitive, resolving the issue of weak screen light during monitor tests.
- These settings guide both real-time alignment and final high-resolution compilation when capture completes.
- **Location**: [`CameraScreen.kt`](file:///Users/rob/.gemini/antigravity/scratch/astrostack/app/src/main/java/com/astrostack/app/ui/CameraScreen.kt) & [`CameraViewModel.kt`](file:///Users/rob/.gemini/antigravity/scratch/astrostack/app/src/main/java/com/astrostack/app/viewmodel/CameraViewModel.kt)

### 12. Streamlined Result Flow (Legacy Screen Bypass)
- When a capture session is stopped, the final stacked image is compiled and saved immediately.
- The app then navigates to the Stacking screen, which automatically detects the completed stacked image and bypasses the legacy parameters setup screen.
- Instead, it directly presents the **Completed Result Screen**, showcasing the final stacked image alongside Plate Solving (celestial annotations) and multi-format exports (PNG, TIFF, FITS).
- **Location**: [`StackingViewModel.kt`](file:///Users/rob/.gemini/antigravity/scratch/astrostack/app/src/main/java/com/astrostack/app/viewmodel/StackingViewModel.kt)

### 13. Master Dark Calibration Wizard
- Added a calibration card to the camera settings pane exposing an **active indicator tag** (`● Master Dark Active` / `● No Dark Reference Profile`).
- Created a **Calibration Dialog Wizard** instructing the user to cover their camera lens completely and tap Start. 
- Automatically executes a background capture sequence (5 frames), averages their red, green, and blue values pixel-by-pixel, and writes:
  - `master_dark_full.png` (full resolution for offline stacking).
  - `master_dark_preview.png` (1/4 scale for live preview subtraction).
- In both live and offline loops, these maps are subtracted in-place immediately after frame decoding to strip out sensor thermal glow and hot pixels before star matching and stacking occur.
- **Locations**: [`CaptureController.kt`](file:///Users/rob/.gemini/antigravity/scratch/astrostack/app/src/main/java/com/astrostack/app/camera/CaptureController.kt) & [`ImageStacker.kt`](file:///Users/rob/.gemini/antigravity/scratch/astrostack/app/src/main/java/com/astrostack/app/stacking/ImageStacker.kt)

### 14. Star De-rotation (Alt-Az / Tripod Rigid Transform)
- Upgraded the alignment logic to solve translation AND rotation rigid transforms.
- Uses centroid-centered coordinates and a closed-form covariance solver to compute rotation angles (`angleRad` via `atan2(sin, cos)`) and translations `(tx, ty)`.
- Applies a RANSAC-like projection residual threshold filtering outliers (star mismatches/spurious detections) to refine alignment to sub-pixel accuracy.
- Rotates and translates the image centered at its absolute middle using `Matrix` deformation.
- **Location**: [StarAligner.kt](file:///Users/rob/.gemini/antigravity/scratch/astrostack/app/src/main/java/com/astrostack/app/stacking/StarAligner.kt)

### 15. Arcsinh Color-Preserving Stretch
- Added an alternative stretching algorithm based on the **inverse hyperbolic sine** function (`asinh`).
- Unlike histogram-based stretching which can clip bright star colors to white, arcsinh preserves the **chrominance ratios** (R:G:B) of each pixel while boosting faint detail.
- The user can toggle between **Histogram (STF)** and **Arcsinh (Color)** via a segmented control in the Stacking Settings panel.
- Applied consistently across live preview, offline stacking, and single-frame scouting.
- **Location**: [HistogramStretch.kt](file:///Users/rob/.gemini/antigravity/scratch/astrostack/app/src/main/java/com/astrostack/app/stacking/HistogramStretch.kt)

### 16. Cosmetic Hot Pixel Correction
- Detects isolated hot pixels by comparing each pixel's luminance against a 3x3 median filter of its neighbors.
- If the deviation exceeds a threshold (currently 50 counts), the pixel is replaced with the median value.
- Runs **in-place** immediately after frame decoding, before star detection, alignment, or stacking.
- Active in both live capture and offline stacking pipelines.
- **Location**: [CaptureController.kt](file:///Users/rob/.gemini/antigravity/scratch/astrostack/app/src/main/java/com/astrostack/app/camera/CaptureController.kt) and [ImageStacker.kt](file:///Users/rob/.gemini/antigravity/scratch/astrostack/app/src/main/java/com/astrostack/app/stacking/ImageStacker.kt)

### 17. FWHM Sharpness-Based Frame Rejection
- Measures the **Full Width at Half Maximum** (FWHM) of detected star profiles to quantify frame sharpness.
- The reference frame establishes a baseline FWHM. Any subsequent frame with FWHM > 1.4x the reference is automatically rejected as blurry (e.g., from wind, vibration, or poor seeing).
- Rejected frames increment the "REJECTED" counter in the live statistics overlay.
- **Location**: [StarAligner.kt](file:///Users/rob/.gemini/antigravity/scratch/astrostack/app/src/main/java/com/astrostack/app/stacking/StarAligner.kt)

### 18. Quick Scout Frame (Night Sight Scouting)
- Added a **Scout** button in the top bar that captures a single high-sensitivity frame (1.5s, ISO 3200) for rapid target finding and framing.
- The frame is decoded, cosmetically corrected, and auto-stretched using the user's selected stretch type, then saved and displayed on the result screen.
- Useful for quickly checking if the target is in the field of view before committing to a long stacking session.
- On Pixel 9, the app also checks for Camera2 Night Extension support.
- **Location**: [CameraViewModel.kt](file:///Users/rob/.gemini/antigravity/scratch/astrostack/app/src/main/java/com/astrostack/app/viewmodel/CameraViewModel.kt) and [CaptureController.kt](file:///Users/rob/.gemini/antigravity/scratch/astrostack/app/src/main/java/com/astrostack/app/camera/CaptureController.kt)

### 19. Light Pollution Gradient Removal
- Automatically isolates sky background from stars inside an 8x8 sample tile grid (using pixels at or below the 50th-percentile luminance).
- Estimates a smooth background light-pollution gradient surface using bilinear interpolation.
- Subtracts the gradient from each pixel in-place, dramatically enhancing contrast for deep-sky objects captured under urban skyglow.
- Controlled via the **Gradient Removal** toggle switch in the Stacking Settings collapsible panel.
- **Location**: [GradientRemoval.kt](file:///Users/rob/.gemini/antigravity/scratch/astrostack/app/src/main/java/com/astrostack/app/stacking/GradientRemoval.kt), [CaptureController.kt](file:///Users/rob/.gemini/antigravity/scratch/astrostack/app/src/main/java/com/astrostack/app/camera/CaptureController.kt), and [ImageStacker.kt](file:///Users/rob/.gemini/antigravity/scratch/astrostack/app/src/main/java/com/astrostack/app/stacking/ImageStacker.kt)

### 20. Flat Frame Calibration
- Implements vignetting (dark corners) and lens dust correction by dividing science frames by a normalized master flat.
- Features a **Flat Frame Calibration** card under the Calibration section with an indicator state tag (`Master Flat Active` / `No Flat Reference Profile`).
- The Flat Calibration wizard captures 10 frames against a bright, evenly lit surface, computes their pixel-wise average, and normalizes it against its mean brightness.
- Corrects both live previews and final high-resolution offline stacks.
- **Location**: [CaptureController.kt](file:///Users/rob/.gemini/antigravity/scratch/astrostack/app/src/main/java/com/astrostack/app/camera/CaptureController.kt) and [ImageStacker.kt](file:///Users/rob/.gemini/antigravity/scratch/astrostack/app/src/main/java/com/astrostack/app/stacking/ImageStacker.kt)

---

## Verification and Compile Checks

All modules compiled cleanly under Gradle with all unit tests passing.

```
BUILD SUCCESSFUL in 10s
63 actionable tasks: 22 executed, 41 up-to-date
```

### Manual Verification Steps
1. **Stretch Type Toggle**: In the Stacking Settings panel, verify the Stretch Type segmented control shows Histogram (STF) and Arcsinh (Color) chips.
2. **Gradient Removal Toggle**: Toggle the **Gradient Removal** switch on/off in the Stacking Settings panel and check that city skyglow is subtracted from the preview.
3. **Quick Scout**: Tap the Scout button in the top bar. Verify a single frame is captured, stretched, and displayed on the result screen.
4. **Master Dark Calibration**: Tap Calibrate under Dark Frame Calibration, cover the lens, tap Start, watch the 5-frame progress.
5. **Master Flat Calibration**: Tap Calibrate under Flat Frame Calibration, point at a bright even surface, tap Start, watch the 10-frame progress. Confirm status indicator turns green.
6. **De-rotation Test**: Rotate phone slightly while pointing at stars. Check logcat with `adb logcat -s AstroStack`.

