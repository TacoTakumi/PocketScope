package com.pocketscope.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Tests for [RawCaptureSession] pixel extraction logic.
 *
 * The Camera2 capture pipeline cannot be unit tested without hardware,
 * but the raw pixel extraction logic (extractRawPixels) is tested here
 * with synthetic byte buffers simulating contiguous and padded row layouts.
 */
class RawCaptureSessionTest {

    // --- Contiguous buffer (rowStride == width * pixelStride) ---

    @Test
    fun `extractRawPixels with contiguous buffer returns all bytes`() {
        val width = 4
        val height = 3
        val pixelStride = 2  // 16-bit
        val rowStride = width * pixelStride  // 8, no padding

        // Create buffer: 4 pixels * 2 bytes * 3 rows = 24 bytes
        val data = ByteArray(rowStride * height) { it.toByte() }
        val buffer = ByteBuffer.wrap(data)

        val result = RawCaptureSession.extractRawPixels(buffer, rowStride, pixelStride, width, height)

        assertEquals(width * height * pixelStride, result.size)
        assertArrayEquals(data, result)
    }

    // --- Padded buffer (rowStride > width * pixelStride) ---

    @Test
    fun `extractRawPixels with padded buffer strips padding bytes`() {
        val width = 4
        val height = 3
        val pixelStride = 2  // 16-bit
        val rowBytes = width * pixelStride  // 8 bytes of actual pixel data per row
        val rowStride = 16  // 8 bytes padding per row

        // Build buffer with padding
        val data = ByteArray(rowStride * height)
        for (row in 0 until height) {
            for (col in 0 until rowBytes) {
                data[row * rowStride + col] = (row * rowBytes + col).toByte()
            }
            // Padding bytes filled with 0xFF to make them detectable
            for (pad in rowBytes until rowStride) {
                data[row * rowStride + pad] = 0xFF.toByte()
            }
        }
        val buffer = ByteBuffer.wrap(data)

        val result = RawCaptureSession.extractRawPixels(buffer, rowStride, pixelStride, width, height)

        // Output should be rowBytes * height = 8 * 3 = 24 bytes (no padding)
        assertEquals(rowBytes * height, result.size)

        // Verify no padding bytes leaked through
        val expected = ByteArray(rowBytes * height)
        for (row in 0 until height) {
            for (col in 0 until rowBytes) {
                expected[row * rowBytes + col] = (row * rowBytes + col).toByte()
            }
        }
        assertArrayEquals(expected, result)
    }

    // --- Output size verification ---

    @Test
    fun `extractRawPixels output size is exactly width times height times pixelStride`() {
        val width = 100
        val height = 50
        val pixelStride = 2
        val rowStride = width * pixelStride  // contiguous

        val data = ByteArray(rowStride * height)
        val buffer = ByteBuffer.wrap(data)

        val result = RawCaptureSession.extractRawPixels(buffer, rowStride, pixelStride, width, height)

        assertEquals(width * height * pixelStride, result.size)
    }

    @Test
    fun `extractRawPixels with padded buffer output size equals unpadded size`() {
        val width = 100
        val height = 50
        val pixelStride = 2
        val rowBytes = width * pixelStride  // 200
        val rowStride = 256  // padded to 256-byte alignment

        val data = ByteArray(rowStride * height)
        val buffer = ByteBuffer.wrap(data)

        val result = RawCaptureSession.extractRawPixels(buffer, rowStride, pixelStride, width, height)

        assertEquals(rowBytes * height, result.size)
    }

    // --- Single pixel ---

    @Test
    fun `extractRawPixels with 1x1 image returns 2 bytes`() {
        val data = byteArrayOf(0x42, 0x43)
        val buffer = ByteBuffer.wrap(data)

        val result = RawCaptureSession.extractRawPixels(buffer, 2, 2, 1, 1)

        assertEquals(2, result.size)
        assertArrayEquals(data, result)
    }
}
