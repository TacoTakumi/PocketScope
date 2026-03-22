package com.pocketscope.indi.properties

import java.io.Writer

/**
 * Minimal XML writer for INDI protocol output.
 *
 * INDI uses plain XML without namespaces, DTDs, or processing instructions.
 * Standard XML libraries (xmlutil, etc.) add namespace prefixes and produce
 * self-closing tags that INDI clients (KStars/Ekos) reject.
 *
 * This writer produces exactly the XML that INDI clients expect.
 */
class IndiXmlWriter(private val writer: Writer) {

    fun startElement(tag: String) {
        writer.write("<$tag")
    }

    fun attribute(name: String, value: String) {
        writer.write(" $name=\"${escapeXml(value)}\"")
    }

    fun closeStartTag() {
        writer.write(">")
        depth++
    }

    fun text(content: String) {
        writer.write(escapeXml(content))
    }

    private var depth = 0

    fun endElement(tag: String) {
        writer.write("</$tag>")
        depth--
        if (depth <= 0) {
            writer.write("\n")  // Newline after top-level elements per INDI convention
            depth = 0
        }
    }

    /** Write an empty element: <tag attr="val"></tag> (no self-closing) */
    fun emptyElement(tag: String, vararg attrs: Pair<String, String>) {
        writer.write("<$tag")
        for ((name, value) in attrs) {
            writer.write(" $name=\"${escapeXml(value)}\"")
        }
        writer.write("></$tag>")
    }

    fun flush() {
        writer.flush()
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
