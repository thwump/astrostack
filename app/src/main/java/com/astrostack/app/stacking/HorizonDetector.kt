package com.astrostack.app.stacking

import android.graphics.Bitmap
import javax.inject.Inject
import kotlin.math.max

/**
 * Horizon detection and Dual-Layer Sky/Foreground blending.
 *
 * Enables pin-sharp star alignment in the celestial sky while keeping trees,
 * mountains, and landscape foreground elements static with zero motion blur.
 */
class HorizonDetector @Inject constructor() {

    /**
     * Computes a feathered sky mask for [bitmap].
     * Returns a FloatArray of size (width * height) where:
     *  - 1.0f = 100% Sky (warped with celestial star alignment)
     *  - 0.0f = 100% Ground (static, zero translation/rotation)
     *  - [0.0..1.0] = Smooth feathered transition along the horizon line.
     */
    fun computeSkyMask(bitmap: Bitmap, featherRadius: Int = 20): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val size = width * height
        val pixels = IntArray(size)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Compute row-wise average luminance and row-to-row gradient
        val rowLuma = FloatArray(height)
        for (y in 0 until height) {
            var sum = 0.0
            val rowOffset = y * width
            for (x in 0 until width) {
                val pix = pixels[rowOffset + x]
                val r = (pix shr 16) and 0xFF
                val g = (pix shr 8) and 0xFF
                val b = pix and 0xFF
                sum += (0.2126 * r + 0.7152 * g + 0.0722 * b)
            }
            rowLuma[y] = (sum / width).toFloat()
        }

        // Find the strongest horizontal gradient / transition line in the lower 70% of the frame
        val startY = (height * 0.25f).toInt()
        val endY = (height * 0.90f).toInt()
        var maxGrad = 0f
        var horizonY = (height * 0.65f).toInt() // default fallback to 65% height

        for (y in startY until endY) {
            val grad = kotlin.math.abs(rowLuma[y] - rowLuma[y + 1])
            if (grad > maxGrad) {
                maxGrad = grad
                horizonY = y
            }
        }

        val mask = FloatArray(size)
        val fRadius = max(5, featherRadius)
        val topY = (horizonY - fRadius).coerceAtLeast(0)
        val botY = (horizonY + fRadius).coerceAtMost(height - 1)
        val bandHeight = (botY - topY).toFloat().coerceAtLeast(1f)

        for (y in 0 until height) {
            val rowOffset = y * width
            val alpha = when {
                y <= topY -> 1.0f // Pure Sky
                y >= botY -> 0.0f // Pure Ground
                else -> {
                    // Smooth cosine feathering from 1.0 down to 0.0
                    val t = (y - topY) / bandHeight
                    (0.5f * (1.0f + kotlin.math.cos(t * Math.PI).toFloat())).coerceIn(0f, 1f)
                }
            }
            for (x in 0 until width) {
                mask[rowOffset + x] = alpha
            }
        }

        return mask
    }

    /**
     * Blends [warpedSky] and [staticGround] using [skyMask].
     */
    fun blend(warpedSky: Bitmap, staticGround: Bitmap, skyMask: FloatArray): Bitmap {
        val width = warpedSky.width
        val height = warpedSky.height
        val size = width * height

        val skyPixels = IntArray(size)
        val gndPixels = IntArray(size)
        val outPixels = IntArray(size)

        warpedSky.getPixels(skyPixels, 0, width, 0, 0, width, height)
        staticGround.getPixels(gndPixels, 0, width, 0, 0, width, height)

        for (i in 0 until size) {
            val alpha = skyMask[i]
            if (alpha >= 0.999f) {
                outPixels[i] = skyPixels[i]
            } else if (alpha <= 0.001f) {
                outPixels[i] = gndPixels[i]
            } else {
                val invAlpha = 1.0f - alpha

                val sp = skyPixels[i]
                val gp = gndPixels[i]

                val sr = (sp shr 16) and 0xFF
                val sg = (sp shr 8) and 0xFF
                val sb = sp and 0xFF

                val gr = (gp shr 16) and 0xFF
                val gg = (gp shr 8) and 0xFF
                val gb = gp and 0xFF

                val r = (sr * alpha + gr * invAlpha + 0.5f).toInt().coerceIn(0, 255)
                val g = (sg * alpha + gg * invAlpha + 0.5f).toInt().coerceIn(0, 255)
                val b = (sb * alpha + gb * invAlpha + 0.5f).toInt().coerceIn(0, 255)

                outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(outPixels, 0, width, 0, 0, width, height)
        return out
    }
}
