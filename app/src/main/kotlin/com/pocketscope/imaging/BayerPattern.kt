package com.pocketscope.imaging

/**
 * Maps Android Camera2 CFA (Color Filter Arrangement) integer constants
 * to FITS BAYERPAT string values.
 *
 * Camera2 CFA constants are defined in [android.hardware.camera2.CameraCharacteristics]
 * SENSOR_INFO_COLOR_FILTER_ARRANGEMENT:
 * - 0 = RGGB
 * - 1 = GRBG
 * - 2 = GBRG
 * - 3 = BGGR
 *
 * FITS BAYERPAT is used by astronomy software (Siril, PixInsight, Ekos) to correctly
 * debayer raw sensor data during calibration workflows.
 */
object BayerPattern {

    /**
     * Converts a Camera2 CFA arrangement integer to a FITS BAYERPAT string.
     *
     * @param cfaArrangement Camera2 SENSOR_INFO_COLOR_FILTER_ARRANGEMENT value (0-3)
     * @return FITS BAYERPAT string ("RGGB", "GRBG", "GBRG", or "BGGR").
     *         Defaults to "RGGB" for unknown values.
     */
    fun fromCamera2(cfaArrangement: Int): String = when (cfaArrangement) {
        0 -> "RGGB"
        1 -> "GRBG"
        2 -> "GBRG"
        3 -> "BGGR"
        else -> "RGGB"
    }
}
