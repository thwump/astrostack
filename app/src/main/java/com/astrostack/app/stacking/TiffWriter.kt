package com.astrostack.app.stacking

import java.io.OutputStream

object TiffWriter {
    fun writeRgbTiff(os: OutputStream, width: Int, height: Int, pixels: IntArray) {
        val bos = if (os is java.io.BufferedOutputStream) os else java.io.BufferedOutputStream(os)
        
        // 1. Write Header (8 bytes)
        // II (Little Endian magic), 42 (TIFF magic), offset to first IFD (byte 8)
        bos.write(byteArrayOf(0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00))
        
        // 2. Write IFD (Image File Directory)
        // We define 10 entries (12 bytes each) + 2 bytes count + 4 bytes next IFD offset (0)
        val numEntries = 10
        val ifdSize = 2 + numEntries * 12 + 4
        // The IFD is placed right at byte 8, occupying bytes 8..133.
        // BitsPerSample data (3 SHORTs = 6 bytes) is placed at byte 134.
        val bitsPerSampleOffset = 8 + ifdSize
        // Image data starts at byte 140.
        val imageDataOffset = bitsPerSampleOffset + 6
        val imageDataSize = width * height * 3
        
        writeShortLE(bos, numEntries)
        
        // Write entries (must be sorted by Tag ID ascending!)
        // 256 (0x0100): ImageWidth (LONG)
        writeEntry(bos, 256, 4, 1, width)
        // 257 (0x0101): ImageLength (LONG)
        writeEntry(bos, 257, 4, 1, height)
        // 258 (0x0102): BitsPerSample (SHORT, count 3, offset to 8,8,8)
        writeEntry(bos, 258, 3, 3, bitsPerSampleOffset)
        // 259 (0x0103): Compression (SHORT, value 1 = none)
        writeEntry(bos, 259, 3, 1, 1)
        // 262 (0x0106): PhotometricInterpretation (SHORT, value 2 = RGB)
        writeEntry(bos, 262, 3, 1, 2)
        // 273 (0x0111): StripOffsets (LONG, offset to raw image data)
        writeEntry(bos, 273, 4, 1, imageDataOffset)
        // 277 (0x0115): SamplesPerPixel (SHORT, value 3 = RGB)
        writeEntry(bos, 277, 3, 1, 3)
        // 278 (0x0116): RowsPerStrip (LONG, height of image)
        writeEntry(bos, 278, 4, 1, height)
        // 279 (0x0117): StripByteCounts (LONG, total pixel bytes)
        writeEntry(bos, 279, 4, 1, imageDataSize)
        // 284 (0x011C): PlanarConfiguration (SHORT, value 1 = chunky format R1G1B1...)
        writeEntry(bos, 284, 3, 1, 1)
        
        // Next IFD offset (0 = none)
        writeIntLE(bos, 0)
        
        // Write BitsPerSample extra data (three 16-bit SHORTs: 8, 8, 8)
        writeShortLE(bos, 8)
        writeShortLE(bos, 8)
        writeShortLE(bos, 8)
        
        // Write Image Data (R, G, B order per pixel, chunky planar configuration)
        val buffer = ByteArray(width * 3)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                val argb = pixels[offset + x]
                buffer[x * 3] = ((argb shr 16) and 0xFF).toByte()
                buffer[x * 3 + 1] = ((argb shr 8) and 0xFF).toByte()
                buffer[x * 3 + 2] = (argb and 0xFF).toByte()
            }
            bos.write(buffer)
        }
        
        bos.flush()
    }
    
    private fun writeEntry(bos: OutputStream, tag: Int, type: Int, count: Int, value: Int) {
        writeShortLE(bos, tag)
        writeShortLE(bos, type)
        writeIntLE(bos, count)
        if (type == 3 && count == 1) {
            writeShortLE(bos, value)
            writeShortLE(bos, 0) // padding to 4 bytes
        } else {
            writeIntLE(bos, value)
        }
    }
    
    private fun writeShortLE(bos: OutputStream, s: Int) {
        bos.write(s and 0xFF)
        bos.write((s shr 8) and 0xFF)
    }
    
    private fun writeIntLE(bos: OutputStream, i: Int) {
        bos.write(i and 0xFF)
        bos.write((i shr 8) and 0xFF)
        bos.write((i shr 16) and 0xFF)
        bos.write((i shr 24) and 0xFF)
    }
}
