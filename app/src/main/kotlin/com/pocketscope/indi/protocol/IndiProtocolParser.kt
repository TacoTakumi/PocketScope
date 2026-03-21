package com.pocketscope.indi.protocol

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.Collections

/**
 * Streaming parser for the INDI XML protocol.
 *
 * INDI sends independent XML elements over a persistent TCP stream without a
 * single root element. Standard XML parsers require a single root, so we
 * prepend a dummy `<indi>` tag via [SequenceInputStream] to satisfy the parser.
 *
 * Uses [XmlPullParser] for event-driven streaming -- no buffering the entire
 * document, which is essential for a never-ending TCP stream.
 */
class IndiProtocolParser(inputStream: InputStream) {

    private val parser: XmlPullParser

    init {
        val prefix = "<indi>".byteInputStream()
        val combined = SequenceInputStream(Collections.enumeration(listOf(prefix, inputStream)))
        parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(combined, "UTF-8")
    }

    /**
     * Reads the XML stream, invoking [onElement] for each top-level INDI command
     * encountered (except the synthetic `<indi>` root).
     *
     * For command vectors (tags starting with "new", e.g., newNumberVector,
     * newSwitchVector), child elements (oneNumber, oneSwitch, etc.) are collected
     * into the elements map as childName -> textContent.
     *
     * For non-command tags (e.g., getProperties), the elements map is empty.
     *
     * This will block on the underlying InputStream when no data is available,
     * making it suitable for use on a coroutine dispatcher (Dispatchers.IO).
     *
     * @param onElement callback with tag name, attributes map, and child elements map
     */
    fun parseStream(onElement: (name: String, attributes: Map<String, String>, elements: Map<String, String>) -> Unit) {
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name != "indi") {
                val tagName = parser.name
                val attributes = buildMap {
                    for (i in 0 until parser.attributeCount) {
                        put(parser.getAttributeName(i), parser.getAttributeValue(i))
                    }
                }

                if (tagName.startsWith("new")) {
                    // Command vector: collect child elements (oneNumber, oneSwitch, oneText, etc.)
                    val childElements = mutableMapOf<String, String>()
                    eventType = parser.next()
                    while (!(eventType == XmlPullParser.END_TAG && parser.name == tagName)) {
                        if (eventType == XmlPullParser.START_TAG) {
                            // Read child element name attribute
                            val childName = parser.getAttributeValue(null, "name") ?: ""
                            // Advance to TEXT content
                            eventType = parser.next()
                            val textContent = if (eventType == XmlPullParser.TEXT) {
                                val text = parser.text.trim()
                                eventType = parser.next() // skip to END_TAG of child
                                text
                            } else {
                                ""
                            }
                            childElements[childName] = textContent
                        }
                        eventType = parser.next()
                    }
                    onElement(tagName, attributes, childElements)
                } else {
                    // Non-command tag (e.g., getProperties, enableBLOB)
                    // For tags with text content (like enableBLOB), capture it
                    eventType = parser.next()
                    val textElements = if (eventType == XmlPullParser.TEXT) {
                        val text = parser.text.trim()
                        if (text.isNotEmpty()) mapOf("__text__" to text) else emptyMap()
                    } else {
                        emptyMap()
                    }
                    onElement(tagName, attributes, textElements)
                }
            }
            eventType = parser.next()
        }
    }
}
