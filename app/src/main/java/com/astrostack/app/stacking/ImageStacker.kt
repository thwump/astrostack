package com.astrostack.app.stacking

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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

        val width = bitmaps[0].width
        val height = bitmaps[0].height

        // ── 2. Align frames ────────────────────────────────────────────────────
        val aligned: List<Bitmap> = if (config.alignFrames && files.size > 1) {
            alignFrames(bitmaps, config, onProgress = { p -> onProgress(0.1f + p * 0.3f) })
        } else {
            bitmaps
        }
        onProgress(0.4f)

        // ── 3. Stack in horizontal strips ─────────────────────────────────────
        val resultPixels = IntArray(width * height)
        val stripHeight = max(1, height / config.tileStripCount)
        val floatBuffers = Array(aligned.size) { FloatArray(width * stripHeight * 3) }

        for (stripStart in 0 until height step stripHeight) {
            val stripEnd = min(height, stripStart + stripHeight)
            val rows = stripEnd - stripStart

            // Fill float buffers for this strip
            for (frameIdx in aligned.indices) {
                val bmp = aligned[frameIdx]
                val strip = IntArray(width * rows)
                bmp.getPixels(strip, 0, width, 0, stripStart, width, rows)
                argbToLinearFloat(strip, floatBuffers[frameIdx], width * rows)
            }

            // Stack this strip
            val stackedStrip = stackStrip(floatBuffers, width, rows, config)

            // Write result pixels
            linearFloatToArgb(stackedStrip, resultPixels, stripStart * width, width * rows)

            val progress = 0.4f + (stripEnd.toFloat() / height) * 0.5f
            withContext(Dispatchers.Main) { onProgress(progress) }
        }

        // ── 4. Build result bitmap ─────────────────────────────────────────────
        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(resultPixels, 0, width, 0, 0, width, height)
        }
        onProgress(0.9f)

        // ── 5. Histogram stretch ───────────────────────────────────────────────
        val stretched = if (config.skipStretch) resultBitmap else histogramStretch.autoStretch(resultBitmap)
        onProgress(1.0f)

        // Clean up — only recycle resultBitmap if it is NOT the returned bitmap
        // (skipStretch = true means stretched === resultBitmap, so don't double-free)
        aligned.forEach { if (it != bitmaps[0]) it.recycle() }
        bitmaps.drop(1).forEach { it.recycle() }
        if (stretched !== resultBitmap) resultBitmap.recycle()

        stretched
    }

    // ─── Alignment ────────────────────────────────────────────────────────────

    private suspend fun alignFrames(
        bitmaps: List<Bitmap>,
        config: StackingConfig,
        onProgress: suspend (Float) -> Unit,
    ): List<Bitmap> {
        val reference = bitmaps[0]
        val refStars = starAligner.detectStars(reference)
        val result = mutableListOf(reference) // reference is unchanged

        bitmaps.drop(1).forEachIndexed { idx, bmp ->
            val targetStars = starAligner.detectStars(bmp)
            val offset = starAligner.computeTranslation(refStars, targetStars)
            val aligned = if (offset.x != 0f || offset.y != 0f) {
                applyTranslation(bmp, offset.x, offset.y)
            } else {
                bmp
            }
            result.add(aligned)
            onProgress((idx + 1).toFloat() / (bitmaps.size - 1))
        }
        return result
    }

    private fun applyTranslation(src: Bitmap, dx: Float, dy: Float): Bitmap {
        val dst = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dst)
        val matrix = Matrix().also { it.setTranslate(dx, dy) }
        canvas.drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return dst
    }

    // ─── Strip stacking ───────────────────────────────────────────────────────

    /**
     * Stack [frameCount] float strips into a single float strip.
     * Each buffer layout: [R₀G₀B₀, R₁G₁B₁, …] for width*rows pixels.
     */
    private fun stackStrip(
        buffers: Array<FloatArray>,
        width: Int,
        rows: Int,
        config: StackingConfig,
    ): FloatArray {
        val pixelCount = width * rows
        val channels = 3
        val result = FloatArray(pixelCount * channels)

        // Temporary column across frames for one channel of one pixel
        val column = FloatArray(buffers.size)

        for (px in 0 until pixelCount) {
            for (ch in 0 until channels) {
                val idx = px * channels + ch
                for (f in buffers.indices) column[f] = buffers[f][idx]

                result[idx] = when (config.algorithm) {
                    StackingAlgorithm.MEAN -> StackingMath.mean(column)
                    StackingAlgorithm.MEDIAN -> StackingMath.median(column)
                    StackingAlgorithm.SIGMA_CLIPPING ->
                        StackingMath.sigmaClip(column, config.kappa, config.sigmaIterations)
                    StackingAlgorithm.WINSORIZED_SIGMA ->
                        StackingMath.winsorizedSigma(column, config.kappa, config.sigmaIterations)
                    StackingAlgorithm.MAXIMUM -> StackingMath.maximum(column)
                }
            }
        }
        return result
    }

    // ─── Colour space helpers ─────────────────────────────────────────────────

    /**
     * Convert ARGB8888 [argb] to linear-light RGB float [0, 1] in-place.
     * Applies the inverse of the sRGB piecewise gamma function.
     */
    private fun argbToLinearFloat(argb: IntArray, out: FloatArray, count: Int) {
        for (i in 0 until count) {
            val pixel = argb[i]
            val r = ((pixel shr 16) and 0xFF) / 255f
            val g = ((pixel shr 8) and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f
            out[i * 3] = srgbToLinear(r)
            out[i * 3 + 1] = srgbToLinear(g)
            out[i * 3 + 2] = srgbToLinear(b)
        }
    }

    /**
     * Convert linear-light float RGB back to ARGB8888 and write into [out]
     * starting at [outOffset].
     */
    private fun linearFloatToArgb(floats: FloatArray, out: IntArray, outOffset: Int, count: Int) {
        for (i in 0 until count) {
            val r = (linearToSrgb(floats[i * 3]).coerceIn(0f, 1f) * 255 + 0.5f).toInt()
            val g = (linearToSrgb(floats[i * 3 + 1]).coerceIn(0f, 1f) * 255 + 0.5f).toInt()
            val b = (linearToSrgb(floats[i * 3 + 2]).coerceIn(0f, 1f) * 255 + 0.5f).toInt()
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
            // Force sRGB on API 26+ so the decoded pixel values are never
            // silently converted to the device's Display P3 colour space.
            inPreferredColorSpace = srgb
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: throw IllegalArgumentException("Failed to decode ${file.name}")
    }
}
