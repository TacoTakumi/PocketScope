package com.pocketscope.indi.properties

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import nl.adaptivity.xmlutil.XmlWriter

/**
 * INDI property states as defined by the INDI protocol specification.
 */
enum class PropertyState {
    Idle, Ok, Busy, Alert
}

/**
 * Base class for all INDI property types.
 *
 * Properties are stateful objects (following libindi C++ style) that emit
 * updates via Kotlin Flow when their state or value changes. This allows
 * the network layer to broadcast changes to all connected INDI clients.
 *
 * Each property can serialize itself to valid INDI XML via [writeXml],
 * using the xmlutil XmlWriter for correct escaping and formatting.
 */
sealed class IndiProperty(
    val device: String,
    val name: String,
    val label: String,
    val group: String,
    initialState: PropertyState
) {
    private val _updates = MutableSharedFlow<IndiProperty>(replay = 1, extraBufferCapacity = 1)
    val updates: SharedFlow<IndiProperty> = _updates.asSharedFlow()

    var state: PropertyState = initialState
        set(value) {
            field = value
            _updates.tryEmit(this)
        }

    protected fun emitUpdate() {
        _updates.tryEmit(this)
    }

    /**
     * Serializes this property to INDI XML using the given writer.
     * Each subclass produces the appropriate defXxxVector / defXxx elements.
     */
    abstract fun writeXml(writer: XmlWriter)

    /**
     * Writes the common vector-level attributes: device, name, label, group, state.
     */
    protected fun writeCommonAttributes(writer: XmlWriter) {
        writer.attribute(null, "device", null, device)
        writer.attribute(null, "name", null, name)
        writer.attribute(null, "label", null, label)
        writer.attribute(null, "group", null, group)
        writer.attribute(null, "state", null, state.name)
    }
}

/**
 * INDI Text property - holds a string value.
 *
 * Serializes as:
 * ```xml
 * <defTextVector device="..." name="..." label="..." group="..." state="...">
 *   <defText name="..." label="...">value</defText>
 * </defTextVector>
 * ```
 */
class TextProperty(
    device: String,
    name: String,
    label: String,
    group: String,
    initialState: PropertyState,
    value: String
) : IndiProperty(device, name, label, group, initialState) {

    var value: String = value
        set(newValue) {
            field = newValue
            emitUpdate()
        }

    override fun writeXml(writer: XmlWriter) {
        writer.startTag(null, "defTextVector", null)
        writeCommonAttributes(writer)

        writer.startTag(null, "defText", null)
        writer.attribute(null, "name", null, name)
        writer.attribute(null, "label", null, label)
        writer.text(value)
        writer.endTag(null, "defText", null)

        writer.endTag(null, "defTextVector", null)
    }
}

/**
 * INDI Number property - holds a double value with format, min, max, step.
 *
 * The format string follows INDI number formatting conventions (e.g., "%6.2f").
 * Numbers are strictly formatted using [String.format] with the INDI format spec.
 *
 * Serializes as:
 * ```xml
 * <defNumberVector device="..." name="..." label="..." group="..." state="...">
 *   <defNumber name="..." label="..." format="..." min="..." max="..." step="...">value</defNumber>
 * </defNumberVector>
 * ```
 */
class NumberProperty(
    device: String,
    name: String,
    label: String,
    group: String,
    initialState: PropertyState,
    val format: String,
    value: Double,
    val min: Double,
    val max: Double,
    val step: Double
) : IndiProperty(device, name, label, group, initialState) {

    var value: Double = value
        set(newValue) {
            field = newValue
            emitUpdate()
        }

    /**
     * Formats a double using this property's INDI format string.
     * E.g., format="%6.2f" with value=1.5 produces "  1.50".
     */
    fun formatValue(v: Double): String = String.format(format, v)

    override fun writeXml(writer: XmlWriter) {
        writer.startTag(null, "defNumberVector", null)
        writeCommonAttributes(writer)

        writer.startTag(null, "defNumber", null)
        writer.attribute(null, "name", null, name)
        writer.attribute(null, "label", null, label)
        writer.attribute(null, "format", null, format)
        writer.attribute(null, "min", null, formatValue(min))
        writer.attribute(null, "max", null, formatValue(max))
        writer.attribute(null, "step", null, formatValue(step))
        writer.text(formatValue(value))
        writer.endTag(null, "defNumber", null)

        writer.endTag(null, "defNumberVector", null)
    }
}

/**
 * INDI Switch property - holds a map of named on/off options.
 *
 * The rule defines how switches interact: "OneOfMany", "AtMostOne", or "AnyOfMany".
 *
 * Serializes as:
 * ```xml
 * <defSwitchVector device="..." name="..." label="..." group="..." state="..." rule="...">
 *   <defSwitch name="..." label="...">On</defSwitch>
 *   <defSwitch name="..." label="...">Off</defSwitch>
 * </defSwitchVector>
 * ```
 */
class SwitchProperty(
    device: String,
    name: String,
    label: String,
    group: String,
    initialState: PropertyState,
    val rule: String,
    val options: MutableMap<String, Boolean>
) : IndiProperty(device, name, label, group, initialState) {

    override fun writeXml(writer: XmlWriter) {
        writer.startTag(null, "defSwitchVector", null)
        writeCommonAttributes(writer)
        writer.attribute(null, "rule", null, rule)

        for ((optName, isOn) in options) {
            writer.startTag(null, "defSwitch", null)
            writer.attribute(null, "name", null, optName)
            writer.attribute(null, "label", null, optName)
            writer.text(if (isOn) "On" else "Off")
            writer.endTag(null, "defSwitch", null)
        }

        writer.endTag(null, "defSwitchVector", null)
    }
}
