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
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

/**
 * Core image stacking engine.
 *
 * Memory strategy:
 *  Images are processed in horizontal strips ([tileRows] rows at a time) so
 *  that peak RAM ≈ N × width × tileRows × 3 channels × 4 bytes (float).
 *  At 12 MP (4032×3024), 8 strips → ~12 MB/strip × 10 frames = ~120 MB peak.
 */
class ImageStacker @Inject constructor(
    private val starAligner: StarAligner,
    private val histogramStretch: HistogramStretch,
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
        val msg1 = "Reference frame (File 1): Detected ${refStars.size} stars using threshold ${config.starThreshold}."
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
        val offsets = mutableListOf<StarAligner.Offset>()
        
        validBitmaps.add(bitmaps[0])
        offsets.add(StarAligner.Offset(0f, 0f))

        bitmaps.drop(1).forEachIndexed { idx, bmp ->
            val stars = starAligner.detectStars(bmp, starThreshold = config.starThreshold, maxStars = 100)
            if (stars.size >= config.minStarCount) {
                val offset = if (config.alignFrames) {
                    starAligner.computeTranslation(refStars, stars)
                } else {
                    StarAligner.Offset(0f, 0f)
                }
                val qual = starAligner.alignmentQuality(refStars, stars)
                val msg = "Frame ${idx + 2}: Detected ${stars.size} stars. Alignment offset = (${offset.x}, ${offset.y}), Match Quality = ${(qual * 100).toInt()}%."
                android.util.Log.d("AstroStack", msg)
                diagWriter?.println(msg)
                diagWriter?.flush()
                
                validBitmaps.add(bmp)
                offsets.add(offset)
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
            val minX = offsets.minOf { it.x }
            val maxX = offsets.maxOf { it.x }
            val minY = offsets.minOf { it.y }
            val maxY = offsets.maxOf { it.y }

            finalWidth = (maxX - minX).toInt() + width
            finalHeight = (maxY - minY).toInt() + height

            validBitmaps.forEachIndexed { i, bmp ->
                val dx = offsets[i].x - minX
                val dy = offsets[i].y - minY
                val dst = Bitmap.createBitmap(finalWidth, finalHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(dst)
                val matrix = Matrix().also { it.setTranslate(dx, dy) }
                canvas.drawBitmap(bmp, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
                aligned.add(dst)
            }
        } else {
            finalWidth = width
            finalHeight = height
            
            validBitmaps.forEachIndexed { i, bmp ->
                val offset = offsets[i]
                if (offset.x != 0f || offset.y != 0f) {
                    aligned.add(applyTranslation(bmp, offset.x, offset.y))
                } else {
                    aligned.add(bmp)
                }
            }

            if (config.driftHandling == DriftHandling.CROP && config.alignFrames) {
                val left = offsets.maxOf { it.x }.coerceAtLeast(0f).toInt()
                val right = (offsets.minOf { it.x } + width).coerceAtMost(width.toFloat()).toInt()
                val top = offsets.maxOf { it.y }.coerceAtLeast(0f).toInt()
                val bottom = (offsets.minOf { it.y } + height).coerceAtMost(height.toFloat()).toInt()

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
        val stretched = if (config.skipStretch) resultBitmap else histogramStretch.autoStretch(resultBitmap)
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

    // ─── Alignment ────────────────────────────────────────────────────────────

    private fun applyTranslation(src: Bitmap, dx: Float, dy: Float): Bitmap {
        val dst = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dst)
        val matrix = Matrix().also { it.setTranslate(dx, dy) }
        canvas.drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return dst
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

