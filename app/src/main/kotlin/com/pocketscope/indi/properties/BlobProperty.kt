package com.pocketscope.indi.properties

/**
 * INDI BLOB (Binary Large OBject) property type.
 *
 * Used to advertise image data capability to INDI clients. The defBLOBVector
 * is sent during property negotiation so clients know this device can produce
 * image data (FITS files).
 *
 * Unlike other property types, BLOB data is streamed directly to the TCP
 * socket as Base64-encoded setBLOBVector XML -- it does not flow through
 * the SharedFlow update mechanism. This avoids buffering multi-megabyte
 * image data in memory through the reactive pipeline.
 *
 * Serializes as:
 * ```xml
 * <defBLOBVector device="..." name="..." label="..." group="..." state="..." perm="ro">
 *   <defBLOB name="..." label="..."></defBLOB>
 * </defBLOBVector>
 * ```
 */
class BlobProperty(
    device: String,
    name: String,
    label: String,
    group: String,
    initialState: PropertyState,
    val perm: String = "ro"
) : IndiProperty(device, name, label, group, initialState) {

    override fun writeXml(writer: IndiXmlWriter) {
        writer.startElement("defBLOBVector")
        writeCommonAttributes(writer)
        writer.attribute("perm", perm)
        writer.closeStartTag()

        // Must use open+close tags, not self-closing — KStars rejects <defBLOB ... />
        writer.emptyElement("defBLOB", "name" to name, "label" to label)

        writer.endElement("defBLOBVector")
    }

    override fun writeSetXml(writer: IndiXmlWriter) {
        // No-op: BLOBs are streamed directly to TCP, not via SharedFlow.
        // The setBLOBVector XML is constructed and written inline during
        // image capture to avoid buffering large image data.
    }
}
