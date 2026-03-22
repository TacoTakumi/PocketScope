package com.pocketscope.indi.properties

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import nl.adaptivity.xmlutil.XmlWriter

/**
 * Normalizes INDI number format strings to valid Java format strings.
 * INDI allows "%.f" (no precision digit) meaning zero decimal places,
 * but Java's String.format requires "%.0f".
 */
internal fun normalizeIndiFormat(format: String): String =
    format.replace("%.f", "%.0f")

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
     * Serializes this property as a setXxxVector update message.
     * Used to broadcast property changes to connected INDI clients.
     * Each subclass produces the appropriate setXxxVector / oneXxx elements.
     */
    abstract fun writeSetXml(writer: XmlWriter)

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

    override fun writeSetXml(writer: XmlWriter) {
        writer.startTag(null, "setTextVector", null)
        writer.attribute(null, "device", null, device)
        writer.attribute(null, "name", null, name)
        writer.attribute(null, "state", null, state.name)

        writer.startTag(null, "oneText", null)
        writer.attribute(null, "name", null, name)
        writer.text(value)
        writer.endTag(null, "oneText", null)

        writer.endTag(null, "setTextVector", null)
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
    val step: Double,
    val perm: String = "rw"
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
    fun formatValue(v: Double): String = String.format(normalizeIndiFormat(format), v)

    override fun writeXml(writer: XmlWriter) {
        writer.startTag(null, "defNumberVector", null)
        writeCommonAttributes(writer)
        writer.attribute(null, "perm", null, perm)

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

    override fun writeSetXml(writer: XmlWriter) {
        writer.startTag(null, "setNumberVector", null)
        writer.attribute(null, "device", null, device)
        writer.attribute(null, "name", null, name)
        writer.attribute(null, "state", null, state.name)

        writer.startTag(null, "oneNumber", null)
        writer.attribute(null, "name", null, name)
        writer.text(formatValue(value))
        writer.endTag(null, "oneNumber", null)

        writer.endTag(null, "setNumberVector", null)
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
    val options: MutableMap<String, Boolean>,
    val perm: String = "rw"
) : IndiProperty(device, name, label, group, initialState) {

    override fun writeXml(writer: XmlWriter) {
        writer.startTag(null, "defSwitchVector", null)
        writeCommonAttributes(writer)
        writer.attribute(null, "perm", null, perm)
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

    override fun writeSetXml(writer: XmlWriter) {
        writer.startTag(null, "setSwitchVector", null)
        writer.attribute(null, "device", null, device)
        writer.attribute(null, "name", null, name)
        writer.attribute(null, "state", null, state.name)

        for ((optName, isOn) in options) {
            writer.startTag(null, "oneSwitch", null)
            writer.attribute(null, "name", null, optName)
            writer.text(if (isOn) "On" else "Off")
            writer.endTag(null, "oneSwitch", null)
        }

        writer.endTag(null, "setSwitchVector", null)
    }
}

/**
 * A single element within a [NumberVectorProperty].
 *
 * Represents one named numeric value with format, min/max/step constraints.
 * Used for multi-element INDI properties like CCD_INFO (6 elements) and CCD_FRAME (4 elements).
 */
data class NumberElement(
    val name: String,
    val label: String,
    val format: String,
    var value: Double,
    val min: Double,
    val max: Double,
    val step: Double
)

/**
 * INDI Number Vector property - holds multiple named [NumberElement] values.
 *
 * Unlike [NumberProperty] which holds a single value, this class supports
 * multi-element number properties required by the INDI CCD specification:
 * - CCD_INFO: 6 elements (CCD_MAX_X, CCD_MAX_Y, CCD_PIXEL_SIZE, etc.)
 * - CCD_FRAME: 4 elements (X, Y, WIDTH, HEIGHT)
 * - CCD_EXPOSURE: 1 element (CCD_EXPOSURE_VALUE)
 *
 * Serializes as:
 * ```xml
 * <defNumberVector device="..." name="..." label="..." group="..." state="..." perm="...">
 *   <defNumber name="..." label="..." format="..." min="..." max="..." step="...">value</defNumber>
 *   ...
 * </defNumberVector>
 * ```
 */
class NumberVectorProperty(
    device: String,
    name: String,
    label: String,
    group: String,
    initialState: PropertyState,
    val perm: String,
    val elements: MutableList<NumberElement>
) : IndiProperty(device, name, label, group, initialState) {

    /**
     * Finds a [NumberElement] by its INDI name.
     * @return the element, or null if not found
     */
    fun getElement(name: String): NumberElement? = elements.find { it.name == name }

    /**
     * Updates a named element's value and emits a property update.
     * No-op if the element name is not found.
     */
    fun setElementValue(name: String, value: Double) {
        val elem = getElement(name) ?: return
        elem.value = value
        emitUpdate()
    }

    override fun writeXml(writer: XmlWriter) {
        writer.startTag(null, "defNumberVector", null)
        writeCommonAttributes(writer)
        writer.attribute(null, "perm", null, perm)

        for (elem in elements) {
            val fmt = normalizeIndiFormat(elem.format)
            writer.startTag(null, "defNumber", null)
            writer.attribute(null, "name", null, elem.name)
            writer.attribute(null, "label", null, elem.label)
            writer.attribute(null, "format", null, elem.format)
            writer.attribute(null, "min", null, String.format(fmt, elem.min))
            writer.attribute(null, "max", null, String.format(fmt, elem.max))
            writer.attribute(null, "step", null, String.format(fmt, elem.step))
            writer.text(String.format(fmt, elem.value))
            writer.endTag(null, "defNumber", null)
        }

        writer.endTag(null, "defNumberVector", null)
    }

    override fun writeSetXml(writer: XmlWriter) {
        writer.startTag(null, "setNumberVector", null)
        writer.attribute(null, "device", null, device)
        writer.attribute(null, "name", null, name)
        writer.attribute(null, "state", null, state.name)

        for (elem in elements) {
            writer.startTag(null, "oneNumber", null)
            writer.attribute(null, "name", null, elem.name)
            writer.text(String.format(normalizeIndiFormat(elem.format), elem.value))
            writer.endTag(null, "oneNumber", null)
        }

        writer.endTag(null, "setNumberVector", null)
    }
}
