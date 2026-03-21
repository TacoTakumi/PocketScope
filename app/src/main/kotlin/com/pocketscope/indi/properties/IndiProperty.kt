package com.pocketscope.indi.properties

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

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
}

/**
 * INDI Text property - holds a string value.
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
}

/**
 * INDI Number property - holds a double value with format, min, max, step.
 *
 * The format string follows INDI number formatting conventions (e.g., "%6.2f").
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
}

/**
 * INDI Switch property - holds a map of named on/off options.
 *
 * The rule defines how switches interact: "OneOfMany", "AtMostOne", or "AnyOfMany".
 */
class SwitchProperty(
    device: String,
    name: String,
    label: String,
    group: String,
    initialState: PropertyState,
    val rule: String,
    val options: MutableMap<String, Boolean>
) : IndiProperty(device, name, label, group, initialState)
