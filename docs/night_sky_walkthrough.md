# 🌌 AstroStack: Night-Sky Tripod Testing Guide

This guide is designed for your testing session tonight. By mounting your phone on a fixed tripod and using `scrcpy` to mirror the screen on your PC, you can comfortably control the app and monitor real-time alignment and stacking diagnostics.

---

## 💻 Step 1: Setting up Mirroring (`scrcpy`)

Using `scrcpy` allows you to monitor the app and view real-time diagnostics in a side-by-side terminal on your PC.

1. **Connect your phone** to your PC via USB.
2. **Start mirroring**: Open a terminal on your PC and run:
   ```bash
   scrcpy --always-on-top --window-title "AstroStack Viewfinder"
   ```
3. Position the `scrcpy` window on one side of your screen, leaving space for a terminal on the other side.

---

## 🔭 Step 2: Physical Tripod Alignment & Scouting

1. Mount the phone securely on your tripod.
2. Aim it at a clear patch of sky (ideally away from bright streetlights).
3. **Composition tip**: Point your camera slightly ahead of your target constellation (stars drift from east to west).
4. **Quick Scouting Frame 🔭**: Tap the **Scout 🔭** button in the top header.
   - The app will capture a single, fast 1.5s high-ISO frame, apply hot-pixel correction, and auto-stretch it.
   - Use the resulting image on the result screen to verify your target is centered and framed correctly before starting a long sequence.
   - Tap back to return to the viewfinder once framing is verified.

---

## 🧼 Step 3: Calibration Profiles (Dark & Flat Frames)

Perform calibration before capturing your main stack to guarantee clean images:

1. **Master Dark (Noise Reduction):**
   - Under **Dark Frame Calibration**, tap **Calibrate**.
   - Cover your phone's lens completely (or place it face down on a dark surface) and tap **Start**.
   - The app will capture 5 frames, average them, and display `Master Dark Active` (green). This subtracts hot pixels and thermal sensor glow.
2. **Master Flat (Vignetting Correction):**
   - Under **Flat Frame Calibration**, tap **Calibrate**.
   - Place a white t-shirt over the lens and aim at the twilight sky, or point the lens directly at a blank white screen, and tap **Start**.
   - The app will capture 10 frames, compute a normalized flat, and display `Master Flat Active` (green). This corrects dark corners and lens dust.

---

## 📸 Step 4: Camera Settings

On the viewfinder screen, configure your camera settings:

1. **Focus Mode**: Tap **Infinity Focus 🌌** to lock the lens focal distance to infinity.
2. **Exposure Duration**: Look at the suggested exposure limits in the UI (NPF/500 rules) to prevent star trails. Set the exposure slider close to the suggested limit (usually 4s to 10s).
3. **ISO**: Set to `1600` or `3200` to maximize sensor sensitivity.
4. **Auto Stack Photos**: Toggle **ON**.
5. **Save All RAW Frames**: 
   - Set to **OFF** to delete the ~25MB DNG files immediately after they are integrated into the live stack (saves space).
   - Set to **ON** to keep raw frames for future processing in desktop software (e.g. Siril).

---

## ⚙️ Step 5: Advanced Stacking Settings

Click **Show Stacking Settings ⚙** to set up the aligner:

1. **Camera Lens Selector**: If your device has multiple rear cameras (e.g. Pixel 9 Pro), tap your target lens (e.g. `Ultrawide (13mm eq.)`, `Main (24mm eq.)`, `Telephoto (110mm eq.)`). The preview will reload and recalculate the NPF limits instantly.
2. **Drift Handling**: Choose **CROP**. Ensures the final saved image only contains the overlapping aligned region.
3. **Stretch Type**: 
   - **Histogram (STF)**: Auto-stretch optimized for faint nebulosity.
   - **Arcsinh (Color)**: Stretches pixels while preserving correct star color ratios (chrominance) to prevent stars from burning white.
4. **Gradient Removal**: Toggle **ON** if you are shooting from a city. This subtracts uneven gradients caused by streetlights and light pollution.
5. **Min Stars for Stacking**: Keep at **5** or **8**.
6. **Star Sensitivity Threshold**: Default **180** (decrease to **130-150** if there is thin haze/clouds).

---

## 🚀 Step 6: Start Capture & Monitor Diagnostics

1. Tap the **Capture** button.
2. **Immersive View**: Tap **Fullscreen ⛶** in the header to hide settings sliders and watch the stack build up cleanly.
3. **Diagnostics Terminal**: On your PC, open a terminal next to your `scrcpy` window and run:
   ```bash
   adb logcat -s AstroStack
   ```
4. **Read the logs in real-time**:
   - For **Frame 1**:
     `AstroStack: Reference frame (File 1): Detected X stars (FWHM = Y.YYpx) using threshold Z.`
   - For **subsequent frames**:
     `AstroStack: Frame A: Detected B stars. Alignment offset = (dX, dY), Rotation = C.CC°, Match Quality = D%, FWHM = E.EEpx.`
   - **Blurry Frame Rejection**: If a frame is affected by wind or tripod vibration and its FWHM exceeds $1.4 \times$ the reference FWHM, watch for:
     `AstroStack: Frame A: REJECTED. Blurry frame (FWHM = ... > 1.4 * Ref FWHM).`

---

## 💾 Step 7: Stop and Export

1. When the stack looks sufficiently bright and clean, click the floating exit full-screen icon, then tap **Stop & Save**.
2. **Plate Solving**: Enter your Astrometry.net API key (or leave blank for offline simulation) and click **Run Plate Solver** to annotate constellations and major deep-sky objects.
3. **Export**: Tap **TIFF** or **FITS** to save raw floating-point stacks in `Pictures/AstroStack/`.
