package com.pocketscope.camera

/**
 * Result of a RAW_SENSOR capture.
 *
 * Contains the raw 16-bit pixel bytes extracted from Camera2 Image.getPlanes()[0].
 * The Image is already closed when this object is created -- the bytes are a copy.
 *
 * @param rawBytes raw 16-bit pixel data in native (little-endian) byte order
 * @param width image width in pixels
 * @param height image height in pixels
 */
data class CaptureResult(
    val rawBytes: ByteArray,
    val width: Int,
    val height: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CaptureResult) return false
        return rawBytes.contentEquals(other.rawBytes) && width == other.width && height == other.height
    }

    override fun hashCode(): Int {
        var result = rawBytes.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        return result
    }
}
