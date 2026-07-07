package com.astrostack.app.stacking

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object FitsWriter {
    fun writeRgbFits(os: OutputStream, width: Int, height: Int, pixels: IntArray) {
        // FITS headers are composed of 80-character cards grouped in blocks of 2880 bytes.
        val headerLines = mutableListOf<String>()
        headerLines.add("SIMPLE  =                    T / file does conform to FITS standard")
        headerLines.add("BITPIX  =                  -32 / number of bits per data pixel (float)")
        headerLines.add("NAXIS   =                    3 / number of data axes")
        headerLines.add("NAXIS1  = %20d / length of data axis 1 (width)".format(width))
        headerLines.add("NAXIS2  = %20d / length of data axis 2 (height)".format(height))
        headerLines.add("NAXIS3  =                    3 / length of data axis 3 (RGB channels)")
        headerLines.add("EXTEND  =                    T / FITS dataset may contain extensions")
        headerLines.add("END")

        // Construct header bytes padded to a multiple of 2880 bytes
        val totalCards = headerLines.size
        val blocksNeeded = (totalCards * 80 + 2879) / 2880
        val headerBytes = ByteArray(2880 * blocksNeeded) { ' '.toByte() }
        
        for (i in headerLines.indices) {
            val line = headerLines[i].padEnd(80, ' ')
            val lineBytes = line.toByteArray(Charsets.US_ASCII)
            System.arraycopy(lineBytes, 0, headerBytes, i * 80, 80)
        }

        os.write(headerBytes)

        val channelSize = width * height
        // Process channel by channel in Big Endian float format (Red first, then Green, then Blue)
        // Allocating a buffer to speed up writing
        val bufferSize = 32768 // 32KB buffer
        val dataBuffer = ByteBuffer.allocate(bufferSize).order(ByteOrder.BIG_ENDIAN)

        // ── Red Channel ──
        for (i in 0 until channelSize) {
            val r = ((pixels[i] shr 16) and 0xFF) / 255f
            dataBuffer.putFloat(r)
            if (!dataBuffer.hasRemaining()) {
                os.write(dataBuffer.array())
                dataBuffer.clear()
            }
        }
        if (dataBuffer.position() > 0) {
            os.write(dataBuffer.array(), 0, dataBuffer.position())
            dataBuffer.clear()
        }

        // ── Green Channel ──
        for (i in 0 until channelSize) {
            val g = ((pixels[i] shr 8) and 0xFF) / 255f
            dataBuffer.putFloat(g)
            if (!dataBuffer.hasRemaining()) {
                os.write(dataBuffer.array())
                dataBuffer.clear()
            }
        }
        if (dataBuffer.position() > 0) {
            os.write(dataBuffer.array(), 0, dataBuffer.position())
            dataBuffer.clear()
        }

        // ── Blue Channel ──
        for (i in 0 until channelSize) {
            val b = (pixels[i] and 0xFF) / 255f
            dataBuffer.putFloat(b)
            if (!dataBuffer.hasRemaining()) {
                os.write(dataBuffer.array())
                dataBuffer.clear()
            }
        }
        if (dataBuffer.position() > 0) {
            os.write(dataBuffer.array(), 0, dataBuffer.position())
            dataBuffer.clear()
        }

        // ── Padding ──
        // FITS data blocks must also be padded to a multiple of 2880 bytes
        val totalDataBytes = channelSize * 3 * 4L
        val remainder = (totalDataBytes % 2880).toInt()
        if (remainder > 0) {
            val paddingSize = 2880 - remainder
            os.write(ByteArray(paddingSize))
        }
        
        os.flush()
    }
}
