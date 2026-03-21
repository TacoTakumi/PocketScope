package com.pocketscope.imaging

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for BayerPattern Camera2 CFA arrangement to FITS BAYERPAT mapping.
 */
class BayerPatternTest {

    @Test
    fun `fromCamera2 with 0 returns RGGB`() {
        assertEquals("RGGB", BayerPattern.fromCamera2(0))
    }

    @Test
    fun `fromCamera2 with 1 returns GRBG`() {
        assertEquals("GRBG", BayerPattern.fromCamera2(1))
    }

    @Test
    fun `fromCamera2 with 2 returns GBRG`() {
        assertEquals("GBRG", BayerPattern.fromCamera2(2))
    }

    @Test
    fun `fromCamera2 with 3 returns BGGR`() {
        assertEquals("BGGR", BayerPattern.fromCamera2(3))
    }

    @Test
    fun `fromCamera2 with unknown value defaults to RGGB`() {
        assertEquals("RGGB", BayerPattern.fromCamera2(99))
    }
}
