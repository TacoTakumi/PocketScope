package com.pocketscope.imaging

import nom.tam.fits.Fits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tests for FitsConverter raw bytes to FITS conversion.
 */
class FitsConverterTest {

    @Test
    fun `buildFits produces valid FITS file from 4x2 raw bytes`() {
        // Create 4x2 pixel image = 8 pixels, each 16-bit = 16 bytes
        val width = 4
        val height = 2
        val rawBytes = createTestRawBytes(width, height)

        val result = FitsConverter.buildFits(
            rawBytes = rawBytes,
            width = width,
            height = height,
            pixelSizeX = 1.22f,
            pixelSizeY = 1.22f,
            focalLength = 6.81f,
            exposureTimeSec = 5.0,
            isoGain = 800,
            bayerPattern = "RGGB"
        )

        assertNotNull(result)
        assertTrue("FITS output should not be empty", result.isNotEmpty())

        // Parse the FITS output
        val fits = Fits(ByteArrayInputStream(result))
        val hdus = fits.read()
        assertTrue("Should have at least 1 HDU", hdus.size >= 1)

        val header = hdus[0].header
        assertEquals("BITPIX should be 16", 16, header.getIntValue("BITPIX"))
        assertEquals("NAXIS should be 2", 2, header.getIntValue("NAXIS"))
        assertEquals("NAXIS1 should be width", width, header.getIntValue("NAXIS1"))
        assertEquals("NAXIS2 should be height", height, header.getIntValue("NAXIS2"))
        assertEquals("BAYERPAT should be RGGB", "RGGB", header.getStringValue("BAYERPAT"))
        assertEquals("INSTRUME should be PocketScope", "PocketScope", header.getStringValue("INSTRUME"))
        assertEquals("EXPTIME should be 5.0", 5.0, header.getDoubleValue("EXPTIME"), 0.001)
        assertEquals("GAIN should be 800", 800, header.getIntValue("GAIN"))
        assertEquals("XPIXSZ should be 1.22", 1.22, header.getDoubleValue("XPIXSZ"), 0.01)
        assertEquals("YPIXSZ should be 1.22", 1.22, header.getDoubleValue("YPIXSZ"), 0.01)
        assertEquals("FOCALLEN should be 6.81", 6.81, header.getDoubleValue("FOCALLEN"), 0.01)
    }

    /**
     * Creates test raw bytes: little-endian 16-bit shorts for width*height pixels.
     */
    private fun createTestRawBytes(width: Int, height: Int): ByteArray {
        val numPixels = width * height
        val buffer = ByteBuffer.allocate(numPixels * 2)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until numPixels) {
            buffer.putShort((i * 100).toShort())
        }
        return buffer.array()
    }
}
