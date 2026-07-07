package com.astrostack.app.stacking

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.PrintWriter
import java.io.FileWriter
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.astrostack.app.camera.StretchType
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

/**
 * Core image stacking engine.
 *
 * Memory strategy:
 *  Images are processed in horizontal strips ([tileRows] rows at a time) so
 *  that peak RAM ≈ N × width × tileRows × 3 channels × 4 bytes (float).
 *  Saves temporary stacked results without exhausting JVM heap.
 */
class ImageStacker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val starAligner: StarAligner,
    private val histogramStretch: HistogramStretch,
    private val gradientRemoval: GradientRemoval,
) {
    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Load, (optionally) align, and stack [files] according to [config].
     *
     * @param onProgress Called with progress in [0, 1].
     * @return The stacked, stretched [Bitmap].
     */
    suspend fun stack(
        files: List<File>,
        config: StackingConfig,
        onProgress: suspend (Float) -> Unit = {},
    ): Bitmap = withContext(Dispatchers.Default) {
        require(files.size >= 2) { "Need at least 2 frames to stack" }

        onProgress(0f)

        // ── 1. Decode all frames ───────────────────────────────────────────────
        val bitmaps = withContext(Dispatchers.IO) {
            files.map { decodeBitmap(it, config.subsampleFactor) }
        }
        onProgress(0.1f)

        // Load and apply Master Dark if available
        val masterDarkFile = File(context.filesDir, "calibration/master_dark_full.png")
        if (masterDarkFile.exists()) {
            val darkBmp = BitmapFactory.decodeFile(masterDarkFile.absolutePath)
            if (darkBmp != null) {
                bitmaps.forEach { subtractDark(it, darkBmp) }
                darkBmp.recycle()
            }
        }

        // Load and apply Master Flat if available
        val masterFlatFile = File(context.filesDir, "calibration/master_flat_full.png")
        if (masterFlatFile.exists()) {
            val flatBmp = BitmapFactory.decodeFile(masterFlatFile.absolutePath)
            if (flatBmp != null) {
                bitmaps.forEach { divideFlat(it, flatBmp) }
                flatBmp.recycle()
            }
        }

        // Apply cosmetic hot pixel correction
        bitmaps.forEach { applyCosmeticCorrection(it) }

        // Apply gradient removal if enabled
        if (config.enableGradientRemoval) {
            bitmaps.forEach { gradientRemoval.removeGradient(it) }
        }

        val parentDir = files[0].parentFile
        var diagWriter: PrintWriter? = null
        try {
            if (parentDir != null) {
                val diagnosticsFile = File(parentDir, "offline_stacking_diagnostics.txt")
                diagWriter = PrintWriter(FileWriter(diagnosticsFile))
                diagWriter.println("--- Offline Stacking Run Diagnostics ---")
                diagWriter.println("Total Input Files: ${files.size}")
                diagWriter.println("Config: $config")
                diagWriter.flush()
            }
        } catch (e: Exception) {
            android.util.Log.e("AstroStack", "Failed to create offline diagnostics writer", e)
        }

        val width = bitmaps[0].width
        val height = bitmaps[0].height

        // ── 2. Detect stars & Quality check & Calculate offsets ────────────────
        val refStars = starAligner.detectStars(bitmaps[0], starThreshold = config.starThreshold, maxStars = 100)
        val refFwhm = starAligner.calculateAverageFwhm(bitmaps[0], refStars)
        val msg1 = "Reference frame (File 1): Detected ${refStars.size} stars (FWHM = %.2fpx) using threshold ${config.starThreshold}.".format(refFwhm)
        android.util.Log.d("AstroStack", msg1)
        diagWriter?.println(msg1)
        diagWriter?.flush()

        if (refStars.size < config.minStarCount) {
            val msgError = "Reference frame has too few stars (${refStars.size} < ${config.minStarCount}). Stacking aborted."
            diagWriter?.println(msgError)
            diagWriter?.close()
            bitmaps.forEach { it.recycle() }
            throw IllegalArgumentException(msgError)
        }

        val validBitmaps = mutableListOf<Bitmap>()
        val offsets = mutableListOf<StarAligner.RigidTransform>()
        
        validBitmaps.add(bitmaps[0])
        offsets.add(StarAligner.RigidTransform(0f, 0f, 0f))

        var referenceFwhm = refFwhm
        bitmaps.drop(1).forEachIndexed { idx, bmp ->
            val stars = starAligner.detectStars(bmp, starThreshold = config.starThreshold, maxStars = 100)
            if (stars.size >= config.minStarCount) {
                val targetFwhm = starAligner.calculateAverageFwhm(bmp, stars)
                if (referenceFwhm > 0f && targetFwhm > referenceFwhm * 1.4f) {
                    val msg = "Frame ${idx + 2}: REJECTED. Blurry frame (FWHM = %.2fpx > 1.4 * Ref FWHM = %.2fpx).".format(targetFwhm, referenceFwhm)
                    android.util.Log.w("AstroStack", msg)
                    diagWriter?.println(msg)
                    diagWriter?.flush()
                    bmp.recycle()
                    return@forEachIndexed
                }
                if (referenceFwhm == 0f && targetFwhm > 0f) {
                    referenceFwhm = targetFwhm
                }

                val transform = if (config.alignFrames) {
                    starAligner.estimateRigidTransform(refStars, stars, width, height)
                } else {
                    StarAligner.RigidTransform(0f, 0f, 0f)
                }
                val qual = starAligner.rigidAlignmentQuality(refStars, stars, width, height)
                val angleDeg = Math.toDegrees(transform.angleRad.toDouble()).toFloat()
                val msg = "Frame ${idx + 2}: Detected ${stars.size} stars. Alignment offset = (${transform.tx}, ${transform.ty}), Rotation = ${"%.2f".format(angleDeg)}°, Match Quality = ${(qual * 100).toInt()}%, FWHM = %.2fpx.".format(angleDeg, targetFwhm)
                android.util.Log.d("AstroStack", msg)
                diagWriter?.println(msg)
                diagWriter?.flush()
                
                validBitmaps.add(bmp)
                offsets.add(transform)
            } else {
                val msg = "Frame ${idx + 2}: REJECTED. Star count ${stars.size} < minimum ${config.minStarCount}."
                android.util.Log.w("AstroStack", msg)
                diagWriter?.println(msg)
                diagWriter?.flush()
                bmp.recycle()
            }
        }

        if (validBitmaps.size < 2) {
            val msgError = "Not enough high-quality frames remaining after star count filter (only ${validBitmaps.size} left)."
            diagWriter?.println(msgError)
            diagWriter?.close()
            validBitmaps.forEach { it.recycle() }
            throw IllegalArgumentException(msgError)
        }

        diagWriter?.println("Offline stacking completed. Integrated ${validBitmaps.size} frames.")
        diagWriter?.close()

        onProgress(0.3f)

        // ── 3. Align frames based on Drift Handling mode ─────────────────────
        val aligned = mutableListOf<Bitmap>()
        val finalWidth: Int
        val finalHeight: Int
        var cropRect: android.graphics.Rect? = null

        if (config.driftHandling == DriftHandling.MOSAIC && config.alignFrames) {
            val minX = offsets.minOf { it.tx }
            val maxX = offsets.maxOf { it.tx }
            val minY = offsets.minOf { it.ty }
            val maxY = offsets.maxOf { it.ty }

            finalWidth = (maxX - minX).toInt() + width
            finalHeight = (maxY - minY).toInt() + height

            validBitmaps.forEachIndexed { i, bmp ->
                val dx = offsets[i].tx - minX
                val dy = offsets[i].ty - minY
                val dst = Bitmap.createBitmap(finalWidth, finalHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(dst)
                val matrix = Matrix().also {
                    val cx = width / 2f
                    val cy = height / 2f
                    it.postTranslate(-cx, -cy)
                    val angleDeg = Math.toDegrees(offsets[i].angleRad.toDouble()).toFloat()
                    it.postRotate(angleDeg)
                    it.postTranslate(cx + dx, cy + dy)
                }
                canvas.drawBitmap(bmp, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
                aligned.add(dst)
            }
        } else {
            finalWidth = width
            finalHeight = height
            
            validBitmaps.forEachIndexed { i, bmp ->
                val transform = offsets[i]
                if (transform.tx != 0f || transform.ty != 0f || transform.angleRad != 0f) {
                    aligned.add(applyRigidTransform(bmp, transform))
                } else {
                    aligned.add(bmp)
                }
            }

            if (config.driftHandling == DriftHandling.CROP && config.alignFrames) {
                val left = offsets.maxOf { it.tx }.coerceAtLeast(0f).toInt()
                val right = (offsets.minOf { it.tx } + width).coerceAtMost(width.toFloat()).toInt()
                val top = offsets.maxOf { it.ty }.coerceAtLeast(0f).toInt()
                val bottom = (offsets.minOf { it.ty } + height).coerceAtMost(height.toFloat()).toInt()

                if (right > left && bottom > top) {
                    cropRect = android.graphics.Rect(left, top, right, bottom)
                }
            }
        }

        onProgress(0.4f)

        // ── 4. Stack in horizontal strips ─────────────────────────────────────
        val resultPixels = IntArray(finalWidth * finalHeight)
        val stripHeight = max(1, finalHeight / config.tileStripCount)
        val floatBuffers = Array(aligned.size) { FloatArray(finalWidth * stripHeight * 3) }

        for (stripStart in 0 until finalHeight step stripHeight) {
            val stripEnd = min(finalHeight, stripStart + stripHeight)
            val rows = stripEnd - stripStart

            // Fill float buffers for this strip
            for (frameIdx in aligned.indices) {
                val bmp = aligned[frameIdx]
                val strip = IntArray(finalWidth * rows)
                bmp.getPixels(strip, 0, finalWidth, 0, stripStart, finalWidth, rows)
                argbToLinearFloat(strip, floatBuffers[frameIdx], finalWidth * rows)
            }

            // Stack this strip
            val stackedStrip = stackStrip(floatBuffers, finalWidth, rows, config)

            // Write result pixels
            linearFloatToArgb(stackedStrip, resultPixels, stripStart * finalWidth, finalWidth * rows)

            val progress = 0.4f + (stripEnd.toFloat() / finalHeight) * 0.5f
            withContext(Dispatchers.Main) { onProgress(progress) }
        }

        // ── 5. Build result bitmap ─────────────────────────────────────────────
        var resultBitmap = Bitmap.createBitmap(finalWidth, finalHeight, Bitmap.Config.ARGB_8888).also {
            it.setPixels(resultPixels, 0, finalWidth, 0, 0, finalWidth, finalHeight)
        }
        onProgress(0.9f)

        // ── 6. Crop if required ───────────────────────────────────────────────
        cropRect?.let { rect ->
            val cropped = Bitmap.createBitmap(resultBitmap, rect.left, rect.top, rect.width(), rect.height())
            resultBitmap.recycle()
            resultBitmap = cropped
        }

        // ── 7. Histogram stretch ───────────────────────────────────────────────
        val stretched = if (config.skipStretch) {
            resultBitmap
        } else {
            when (config.stretchType) {
                StretchType.HISTOGRAM -> histogramStretch.autoStretch(resultBitmap)
                StretchType.ARCSINH -> histogramStretch.arcsinhStretch(resultBitmap)
            }
        }
        onProgress(1.0f)

        // Clean up
        aligned.forEachIndexed { i, bmp ->
            if (bmp !== validBitmaps[i]) {
                bmp.recycle()
            }
        }
        validBitmaps.forEach { it.recycle() }
        if (stretched !== resultBitmap) resultBitmap.recycle()

        stretched
    }

    private fun applyRigidTransform(src: Bitmap, transform: StarAligner.RigidTransform): Bitmap {
        val dst = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dst)
        val matrix = Matrix().also {
            val cx = src.width / 2f
            val cy = src.height / 2f
            it.postTranslate(-cx, -cy)
            val angleDeg = Math.toDegrees(transform.angleRad.toDouble()).toFloat()
            it.postRotate(angleDeg)
            it.postTranslate(cx + transform.tx, cy + transform.ty)
        }
        canvas.drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return dst
    }

    private fun subtractDark(src: Bitmap, dark: Bitmap) {
        val width = src.width
        val height = src.height
        if (dark.width != width || dark.height != height) return
        
        val size = width * height
        val srcPixels = IntArray(size)
        val darkPixels = IntArray(size)
        src.getPixels(srcPixels, 0, width, 0, 0, width, height)
        dark.getPixels(darkPixels, 0, width, 0, 0, width, height)
        
        for (i in 0 until size) {
            val sp = srcPixels[i]
            val dp = darkPixels[i]
            
            val sr = (sp shr 16) and 0xFF
            val sg = (sp shr 8) and 0xFF
            val sb = sp and 0xFF
            
            val dr = (dp shr 16) and 0xFF
            val dg = (dp shr 8) and 0xFF
            val db = dp and 0xFF
            
            val r = (sr - dr).coerceAtLeast(0)
            val g = (sg - dg).coerceAtLeast(0)
            val b = (sb - db).coerceAtLeast(0)
            
            srcPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        src.setPixels(srcPixels, 0, width, 0, 0, width, height)
    }

    private fun divideFlat(src: Bitmap, flat: Bitmap) {
        val width = src.width
        val height = src.height
        if (flat.width != width || flat.height != height) return

        val size = width * height
        val srcPixels = IntArray(size)
        val flatPixels = IntArray(size)
        src.getPixels(srcPixels, 0, width, 0, 0, width, height)
        flat.getPixels(flatPixels, 0, width, 0, 0, width, height)

        var sumLuma = 0.0
        for (i in 0 until size) {
            val fp = flatPixels[i]
            val fr = (fp shr 16) and 0xFF
            val fg = (fp shr 8) and 0xFF
            val fb = fp and 0xFF
            sumLuma += (0.299 * fr + 0.587 * fg + 0.114 * fb)
        }
        val meanLuma = (sumLuma / size).toFloat()
        if (meanLuma < 1f) return

        for (i in 0 until size) {
            val sp = srcPixels[i]
            val fp = flatPixels[i]

            val sr = (sp shr 16) and 0xFF
            val sg = (sp shr 8) and 0xFF
            val sb = sp and 0xFF

            val fr = ((fp shr 16) and 0xFF).toFloat() / meanLuma
            val fg = ((fp shr 8) and 0xFF).toFloat() / meanLuma
            val fb = (fp and 0xFF).toFloat() / meanLuma

            val r = if (fr > 0.01f) (sr / fr).toInt().coerceIn(0, 255) else sr
            val g = if (fg > 0.01f) (sg / fg).toInt().coerceIn(0, 255) else sg
            val b = if (fb > 0.01f) (sb / fb).toInt().coerceIn(0, 255) else sb

            srcPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        src.setPixels(srcPixels, 0, width, 0, 0, width, height)
    }

    private fun applyCosmeticCorrection(src: Bitmap) {
        val width = src.width
        val height = src.height
        val size = width * height
        val pixels = IntArray(size)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        val outPixels = pixels.copyOf()

        val dx = intArrayOf(-1, 0, 1, -1, 1, -1, 0, 1)
        val dy = intArrayOf(-1, -1, -1, 0, 0, 1, 1, 1)

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val idx = y * width + x
                val p = pixels[idx]
                val luma = (0.2126f * ((p shr 16) and 0xFF) + 0.7152f * ((p shr 8) and 0xFF) + 0.0722f * (p and 0xFF)).toInt()

                var maxNeighborLuma = 0
                var neighborSumR = 0
                var neighborSumG = 0
                var neighborSumB = 0

                for (n in 0 until 8) {
                    val np = pixels[(y + dy[n]) * width + (x + dx[n])]
                    val nl = (0.2126f * ((np shr 16) and 0xFF) + 0.7152f * ((np shr 8) and 0xFF) + 0.0722f * (np and 0xFF)).toInt()
                    if (nl > maxNeighborLuma) {
                        maxNeighborLuma = nl
                    }
                    neighborSumR += (np shr 16) and 0xFF
                    neighborSumG += (np shr 8) and 0xFF
                    neighborSumB += np and 0xFF
                }

                if (luma > maxNeighborLuma + 50) {
                    val r = neighborSumR / 8
                    val g = neighborSumG / 8
                    val b = neighborSumB / 8
                    outPixels[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        src.setPixels(outPixels, 0, width, 0, 0, width, height)
    }

    // ─── Strip stacking ───────────────────────────────────────────────────────

    private fun stackStrip(
        buffers: Array<FloatArray>,
        width: Int,
        rows: Int,
        config: StackingConfig,
    ): FloatArray {
        val pixelCount = width * rows
        val channels = 3
        val result = FloatArray(pixelCount * channels)
        val column = FloatArray(buffers.size)

        for (px in 0 until pixelCount) {
            for (ch in 0 until channels) {
                val idx = px * channels + ch
                for (f in buffers.indices) column[f] = buffers[f][idx]

                val validColumn = column.filter { !it.isNaN() }.toFloatArray()

                result[idx] = if (validColumn.isEmpty()) {
                    Float.NaN
                } else {
                    when (config.algorithm) {
                        StackingAlgorithm.MEAN -> StackingMath.mean(validColumn)
                        StackingAlgorithm.MEDIAN -> StackingMath.median(validColumn)
                        StackingAlgorithm.SIGMA_CLIPPING ->
                            StackingMath.sigmaClip(validColumn, config.kappa, config.sigmaIterations)
                        StackingAlgorithm.WINSORIZED_SIGMA ->
                            StackingMath.winsorizedSigma(validColumn, config.kappa, config.sigmaIterations)
                        StackingAlgorithm.MAXIMUM -> StackingMath.maximum(validColumn)
                    }
                }
            }
        }
        return result
    }

    // ─── Colour space helpers ─────────────────────────────────────────────────

    private fun argbToLinearFloat(argb: IntArray, out: FloatArray, count: Int) {
        for (i in 0 until count) {
            val pixel = argb[i]
            val alpha = (pixel shr 24) and 0xFF
            if (alpha == 0) {
                out[i * 3] = Float.NaN
                out[i * 3 + 1] = Float.NaN
                out[i * 3 + 2] = Float.NaN
                continue
            }
            val r = ((pixel shr 16) and 0xFF) / 255f
            val g = ((pixel shr 8) and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f
            out[i * 3] = srgbToLinear(r)
            out[i * 3 + 1] = srgbToLinear(g)
            out[i * 3 + 2] = srgbToLinear(b)
        }
    }

    private fun linearFloatToArgb(floats: FloatArray, out: IntArray, outOffset: Int, count: Int) {
        for (i in 0 until count) {
            val rVal = floats[i * 3]
            if (rVal.isNaN()) {
                out[outOffset + i] = 0x00000000
                continue
            }
            val gVal = floats[i * 3 + 1]
            val bVal = floats[i * 3 + 2]
            val r = (linearToSrgb(rVal).coerceIn(0f, 1f) * 255 + 0.5f).toInt()
            val g = (linearToSrgb(gVal).coerceIn(0f, 1f) * 255 + 0.5f).toInt()
            val b = (linearToSrgb(bVal).coerceIn(0f, 1f) * 255 + 0.5f).toInt()
            out[outOffset + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun srgbToLinear(v: Float): Float =
        if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)

    private fun linearToSrgb(v: Float): Float =
        if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f

    private fun Float.pow(exp: Float): Float = Math.pow(toDouble(), exp.toDouble()).toFloat()

    // ─── I/O ──────────────────────────────────────────────────────────────────

    private fun decodeBitmap(file: File, subsample: Int): Bitmap {
        val srgb = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = subsample.coerceAtLeast(1)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = srgb
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: throw IllegalArgumentException("Failed to decode ${file.name}")
    }
}

