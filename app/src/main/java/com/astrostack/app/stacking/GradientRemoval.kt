package com.astrostack.app.stacking

import android.graphics.Bitmap
import javax.inject.Inject
import kotlin.math.max

/**
 * Removes smooth light-pollution gradients from astrophotography frames.
 *
 * Algorithm:
 *  1. Divide the image into a grid of tiles.
 *  2. In each tile, estimate the sky background by taking the mean
 *     of the dimmest pixels (below the tile's 50th-percentile luminance),
 *     which excludes stars and bright nebulae.
 *  3. Bilinearly interpolate between tile medians to build a smooth
 *     gradient surface covering the entire image.
 *  4. Subtract the gradient surface from every pixel (clamped to 0).
 *
 * This corrects for uneven illumination from city lights, moon glow,
 * and atmospheric scattering — especially near the horizon.
 */
class GradientRemoval @Inject constructor() {

    companion object {
        private const val GRID_SIZE = 8 // 8×8 grid of sampling tiles
    }

    /**
     * Remove the estimated light-pollution gradient from [bitmap] in-place.
     */
    fun removeGradient(bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        if (width < GRID_SIZE * 2 || height < GRID_SIZE * 2) return

        val size = width * height
        val pixels = IntArray(size)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val tileW = width / GRID_SIZE
        val tileH = height / GRID_SIZE

        // Sample background medians for each tile
        val bgR = Array(GRID_SIZE) { FloatArray(GRID_SIZE) }
        val bgG = Array(GRID_SIZE) { FloatArray(GRID_SIZE) }
        val bgB = Array(GRID_SIZE) { FloatArray(GRID_SIZE) }

        for (ty in 0 until GRID_SIZE) {
            for (tx in 0 until GRID_SIZE) {
                val x0 = tx * tileW
                val y0 = ty * tileH
                val x1 = if (tx == GRID_SIZE - 1) width else x0 + tileW
                val y1 = if (ty == GRID_SIZE - 1) height else y0 + tileH

                val tilePixelCount = (x1 - x0) * (y1 - y0)
                val lumaValues = IntArray(tilePixelCount)
                val rValues = IntArray(tilePixelCount)
                val gValues = IntArray(tilePixelCount)
                val bValues = IntArray(tilePixelCount)
                var idx = 0

                for (py in y0 until y1) {
                    for (px in x0 until x1) {
                        val pix = pixels[py * width + px]
                        val r = (pix shr 16) and 0xFF
                        val g = (pix shr 8) and 0xFF
                        val b = pix and 0xFF
                        rValues[idx] = r
                        gValues[idx] = g
                        bValues[idx] = b
                        // Approximate luminance
                        lumaValues[idx] = (0.299f * r + 0.587f * g + 0.114f * b).toInt()
                        idx++
                    }
                }

                // Find the 50th percentile luminance to separate sky from stars
                val sortedLuma = lumaValues.copyOf(idx)
                sortedLuma.sort()
                val medianLuma = sortedLuma[sortedLuma.size / 2]

                // Collect only background pixels (at or below median luminance)
                var sumR = 0L
                var sumG = 0L
                var sumB = 0L
                var count = 0
                for (i in 0 until idx) {
                    if (lumaValues[i] <= medianLuma) {
                        sumR += rValues[i]
                        sumG += gValues[i]
                        sumB += bValues[i]
                        count++
                    }
                }

                if (count > 0) {
                    bgR[ty][tx] = sumR.toFloat() / count
                    bgG[ty][tx] = sumG.toFloat() / count
                    bgB[ty][tx] = sumB.toFloat() / count
                }
            }
        }

        // Subtract bilinearly interpolated gradient from every pixel
        for (py in 0 until height) {
            // Find vertical tile position and fractional offset
            val tyf = (py.toFloat() / height) * GRID_SIZE - 0.5f
            val ty0 = tyf.toInt().coerceIn(0, GRID_SIZE - 2)
            val ty1 = ty0 + 1
            val fy = (tyf - ty0).coerceIn(0f, 1f)

            for (px in 0 until width) {
                // Find horizontal tile position and fractional offset
                val txf = (px.toFloat() / width) * GRID_SIZE - 0.5f
                val tx0 = txf.toInt().coerceIn(0, GRID_SIZE - 2)
                val tx1 = tx0 + 1
                val fx = (txf - tx0).coerceIn(0f, 1f)

                // Bilinear interpolation of the background
                val interpR = (bgR[ty0][tx0] * (1 - fx) * (1 - fy)
                             + bgR[ty0][tx1] * fx * (1 - fy)
                             + bgR[ty1][tx0] * (1 - fx) * fy
                             + bgR[ty1][tx1] * fx * fy)
                val interpG = (bgG[ty0][tx0] * (1 - fx) * (1 - fy)
                             + bgG[ty0][tx1] * fx * (1 - fy)
                             + bgG[ty1][tx0] * (1 - fx) * fy
                             + bgG[ty1][tx1] * fx * fy)
                val interpB = (bgB[ty0][tx0] * (1 - fx) * (1 - fy)
                             + bgB[ty0][tx1] * fx * (1 - fy)
                             + bgB[ty1][tx0] * (1 - fx) * fy
                             + bgB[ty1][tx1] * fx * fy)

                val i = py * width + px
                val pix = pixels[i]
                val r = max(0, ((pix shr 16) and 0xFF) - interpR.toInt())
                val g = max(0, ((pix shr 8) and 0xFF) - interpG.toInt())
                val b = max(0, (pix and 0xFF) - interpB.toInt())

                pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }
}
