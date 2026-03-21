package com.pocketscope.indi.properties

import nl.adaptivity.xmlutil.XmlWriter

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
 *   <defBLOB name="..." label="..."/>
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

    override fun writeXml(writer: XmlWriter) {
        writer.startTag(null, "defBLOBVector", null)
        writeCommonAttributes(writer)
        writer.attribute(null, "perm", null, perm)

        writer.startTag(null, "defBLOB", null)
        writer.attribute(null, "name", null, name)
        writer.attribute(null, "label", null, label)
        writer.endTag(null, "defBLOB", null)

        writer.endTag(null, "defBLOBVector", null)
    }

    override fun writeSetXml(writer: XmlWriter) {
        // No-op: BLOBs are streamed directly to TCP, not via SharedFlow.
        // The setBLOBVector XML is constructed and written inline during
        // image capture to avoid buffering large image data.
    }
}
