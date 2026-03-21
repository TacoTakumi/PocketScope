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
 * Uses [XmlPullParser] for event-driven streaming — no buffering the entire
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
     * Reads the XML stream, invoking [onElement] for each START_TAG encountered
     * (except the synthetic `<indi>` root). Attributes are collected into a Map.
     *
     * This will block on the underlying InputStream when no data is available,
     * making it suitable for use on a coroutine dispatcher (Dispatchers.IO).
     */
    fun parseStream(onElement: (name: String, attributes: Map<String, String>) -> Unit) {
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name != "indi") {
                val attributes = buildMap {
                    for (i in 0 until parser.attributeCount) {
                        put(parser.getAttributeName(i), parser.getAttributeValue(i))
                    }
                }
                onElement(parser.name, attributes)
            }
            eventType = parser.next()
        }
    }
}
