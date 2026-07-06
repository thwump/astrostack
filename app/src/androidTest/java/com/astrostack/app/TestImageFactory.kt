package com.astrostack.app

import android.graphics.Bitmap
import android.graphics.Color
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt

/**
 * Factory for creating synthetic [Bitmap]s and PNG files used in instrumented tests.
 *
 * All bitmaps are created with an explicit sRGB colour space so that
 * [android.graphics.BitmapFactory] on wide-gamut devices (Pixel 9 / Display P3) does
 * not silently remap pixel values when decoding the saved PNGs.
 */
object TestImageFactory {

    private val SRGB = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)

    /** A solid-colour [Bitmap] of size [width]×[height] filled with [argbColor]. */
    fun solidColor(argbColor: Int, width: Int = 64, height: Int = 64): Bitmap =
        // hasAlpha=true, colorSpace=SRGB → avoids Display P3 remapping on wide-gamut devices
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888, true, SRGB).also { bmp ->
            bmp.eraseColor(argbColor)
        }

    /**
     * A dark-sky [Bitmap] with peaked circular blobs at [starPositions].
     *
     * Each "star" has a Gaussian-like brightness profile:
     *   centre = 255, ring-1 = 200, ring-2 = 110, ring-3 = 50
     *
     * This ensures the centre pixel is a strict local maximum, which is required
     * by [com.astrostack.app.stacking.StarAligner.detectStars].
     */
    fun starField(
        starPositions: List<Pair<Int, Int>>,
        width: Int = 256,
        height: Int = 256,
        skyLuma: Int = 5,
    ): Bitmap {
        val skyArgb = Color.argb(255, skyLuma, skyLuma, skyLuma)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888, true, SRGB)
        bmp.eraseColor(skyArgb)

        starPositions.forEach { (cx, cy) ->
            for (dy in -4..4) for (dx in -4..4) {
                val nx = cx + dx
                val ny = cy + dy
                if (nx !in 0 until width || ny !in 0 until height) continue
                val dist = sqrt((dx * dx + dy * dy).toDouble())
                val luma = when {
                    dist < 0.5 -> 255   // centre — strict local max
                    dist < 1.5 -> 200
                    dist < 2.5 -> 110
                    dist < 3.5 -> 50
                    else       -> skyLuma
                }.coerceAtLeast(skyLuma)
                bmp.setPixel(nx, ny, Color.argb(255, luma, luma, luma))
            }
        }
        return bmp
    }

    /**
     * Write [bitmap] to a PNG [file] inside [dir] and return the [File].
     * Creates [dir] if it does not exist.
     */
    fun savePng(bitmap: Bitmap, dir: File, name: String): File {
        dir.mkdirs()
        val file = File(dir, name)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    /**
     * Create [count] identical PNG files in [dir], each a solid [argbColor].
     */
    fun solidColorFiles(
        dir: File,
        count: Int,
        argbColor: Int,
        width: Int = 64,
        height: Int = 64,
    ): List<File> {
        val bmp = solidColor(argbColor, width, height)
        val files = (1..count).map { i -> savePng(bmp, dir, "frame_%03d.png".format(i)) }
        bmp.recycle()
        return files
    }

    /** Extract the R, G, B channel values of the centre pixel of [bitmap]. */
    fun centerPixelRgb(bitmap: Bitmap): Triple<Int, Int, Int> {
        val px = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        return Triple(Color.red(px), Color.green(px), Color.blue(px))
    }
}

