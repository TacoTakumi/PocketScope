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
}
