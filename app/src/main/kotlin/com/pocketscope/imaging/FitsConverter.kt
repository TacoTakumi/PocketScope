package com.pocketscope.imaging

import nom.tam.fits.Fits
import nom.tam.fits.FitsFactory
import nom.tam.fits.ImageHDU
import nom.tam.util.BufferedDataOutputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts raw 16-bit Bayer sensor bytes into valid FITS image files
 * with standard astronomy headers.
 *
 * The input raw bytes are little-endian 16-bit unsigned shorts from the
 * Android Camera2 RAW_SENSOR output. nom.tam.fits handles the conversion
 * to big-endian FITS format internally.
 *
 * Output FITS files contain:
 * - BITPIX=16 (16-bit signed integer pixels)
 * - BAYERPAT for client-side debayering
 * - Sensor metadata (pixel size, focal length, exposure, gain)
 * - INSTRUME="PocketScope" for identification
 */
object FitsConverter {

    /**
     * Builds a FITS byte array from raw 16-bit Bayer sensor data.
     *
     * @param rawBytes Raw sensor bytes in little-endian 16-bit format
     * @param width Image width in pixels
     * @param height Image height in pixels
     * @param pixelSizeX Pixel size X in microns (from LensInfo)
     * @param pixelSizeY Pixel size Y in microns (from LensInfo)
     * @param focalLength Focal length in mm (from LensInfo)
     * @param exposureTimeSec Exposure time in seconds
     * @param isoGain ISO gain value
     * @param bayerPattern FITS BAYERPAT string (from BayerPattern.fromCamera2)
     * @return Complete FITS file as byte array
     */
    fun buildFits(
        rawBytes: ByteArray,
        width: Int,
        height: Int,
        pixelSizeX: Float,
        pixelSizeY: Float,
        focalLength: Float,
        exposureTimeSec: Double,
        isoGain: Int,
        bayerPattern: String
    ): ByteArray {
        // Convert little-endian raw bytes to 2D short array
        val buffer = ByteBuffer.wrap(rawBytes)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val shortBuffer = buffer.asShortBuffer()

        val imageData = Array(height) { row ->
            ShortArray(width) { col ->
                shortBuffer.get(row * width + col)
            }
        }

        // Create FITS with ImageHDU
        val fits = Fits()
        val hdu = FitsFactory.hduFactory(imageData) as ImageHDU
        val header = hdu.header

        // Add astronomy headers
        header.addValue("BAYERPAT", bayerPattern, "Bayer color filter pattern")
        header.addValue("XBAYROFF", 0, "Bayer pattern X offset")
        header.addValue("YBAYROFF", 0, "Bayer pattern Y offset")
        header.addValue("XPIXSZ", pixelSizeX.toDouble(), "Pixel size X (microns)")
        header.addValue("YPIXSZ", pixelSizeY.toDouble(), "Pixel size Y (microns)")
        header.addValue("FOCALLEN", focalLength.toDouble(), "Focal length (mm)")
        header.addValue("EXPTIME", exposureTimeSec, "Exposure time (seconds)")
        header.addValue("GAIN", isoGain, "ISO gain value")
        header.addValue("INSTRUME", "PocketScope", "Instrument name")
        header.addValue("ROWORDER", "TOP-DOWN", "Image row order")

        fits.addHDU(hdu)

        // Serialize to byte array
        val byteStream = ByteArrayOutputStream()
        val dataStream = BufferedDataOutputStream(byteStream)
        fits.write(dataStream)
        dataStream.flush()
        return byteStream.toByteArray()
    }
}
