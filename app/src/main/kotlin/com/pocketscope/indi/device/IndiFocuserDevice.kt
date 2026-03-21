package com.pocketscope.indi.device

import com.pocketscope.camera.LensInfo
import com.pocketscope.indi.properties.*

/**
 * INDI Focuser device mapped to Camera2 manual focus control.
 *
 * A single "PocketScope Focuser" device targets whichever lens is currently
 * active. Ekos uses this for its autofocus algorithm, stepping through focus
 * positions and evaluating star FWHM.
 *
 * The focuser maps the lens diopter range (0 to minFocusDistance) onto 1000
 * integer steps:
 * - Step 0 = 0.0 diopters = infinity focus
 * - Step 1000 = minFocusDistance diopters = closest focus
 *
 * Per D-12: Manual focus only (no autofocus).
 * Per D-13: Retains position on reconnection (position is in-memory state).
 * Per D-14: Position resets to 0 when active lens changes.
 */
class IndiFocuserDevice : IndiDevice {

    companion object {
        const val FOCUS_MAX_STEPS = 1000
    }

    override val deviceName = "PocketScope Focuser"

    private val _properties = mutableListOf<IndiProperty>()
    override val properties: List<IndiProperty> get() = _properties

    private var activeLensInfo: LensInfo? = null
    private var currentPosition: Int = 0
    private var focusDirection: String = "FOCUS_OUTWARD"  // default direction

    // --- INDI properties ---

    private val connectionSwitch = SwitchProperty(
        device = deviceName,
        name = "CONNECTION",
        label = "Connection",
        group = "Main Control",
        initialState = PropertyState.Idle,
        rule = "OneOfMany",
        options = mutableMapOf("CONNECT" to false, "DISCONNECT" to true),
        perm = "rw"
    )

    private val absFocusPosition = NumberProperty(
        device = deviceName,
        name = "ABS_FOCUS_POSITION",
        label = "Absolute Position",
        group = "Main Control",
        initialState = PropertyState.Idle,
        format = "%.f",
        value = 0.0,
        min = 0.0,
        max = FOCUS_MAX_STEPS.toDouble(),
        step = 1.0,
        perm = "rw"
    )

    private val relFocusPosition = NumberProperty(
        device = deviceName,
        name = "REL_FOCUS_POSITION",
        label = "Relative Position",
        group = "Main Control",
        initialState = PropertyState.Idle,
        format = "%.f",
        value = 0.0,
        min = 0.0,
        max = FOCUS_MAX_STEPS.toDouble(),
        step = 1.0,
        perm = "rw"
    )

    private val focusMax = NumberProperty(
        device = deviceName,
        name = "FOCUS_MAX",
        label = "Max Travel",
        group = "Main Control",
        initialState = PropertyState.Idle,
        format = "%.f",
        value = FOCUS_MAX_STEPS.toDouble(),
        min = 1000.0,
        max = 1000000.0,
        step = 10000.0,
        perm = "rw"
    )

    private val focusMotion = SwitchProperty(
        device = deviceName,
        name = "FOCUS_MOTION",
        label = "Direction",
        group = "Main Control",
        initialState = PropertyState.Idle,
        rule = "OneOfMany",
        options = mutableMapOf("FOCUS_INWARD" to false, "FOCUS_OUTWARD" to true),
        perm = "rw"
    )

    init {
        _properties.add(connectionSwitch)
        _properties.add(absFocusPosition)
        _properties.add(relFocusPosition)
        _properties.add(focusMax)
        _properties.add(focusMotion)
    }

    // --- Command handling ---

    override fun handleNewProperty(propertyName: String, elements: Map<String, String>) {
        if (activeLensInfo == null && propertyName != "CONNECTION") {
            // Per D-16: reject commands when no active lens
            findProperty(propertyName)?.state = PropertyState.Alert
            return
        }
        when (propertyName) {
            "CONNECTION" -> { /* focuser connects implicitly when a camera connects */ }
            "ABS_FOCUS_POSITION" -> handleAbsPosition(elements)
            "REL_FOCUS_POSITION" -> handleRelPosition(elements)
            "FOCUS_MOTION" -> handleMotionDirection(elements)
            "FOCUS_MAX" -> handleFocusMax(elements)
        }
    }

    private fun handleAbsPosition(elements: Map<String, String>) {
        val requested = elements["FOCUS_ABSOLUTE_POSITION"]?.toIntOrNull() ?: return
        currentPosition = requested.coerceIn(0, FOCUS_MAX_STEPS)
        absFocusPosition.value = currentPosition.toDouble()
        absFocusPosition.state = PropertyState.Ok
    }

    private fun handleRelPosition(elements: Map<String, String>) {
        val steps = elements["FOCUS_RELATIVE_POSITION"]?.toIntOrNull() ?: return
        currentPosition = if (focusDirection == "FOCUS_OUTWARD") {
            (currentPosition + steps)
        } else {
            (currentPosition - steps)
        }.coerceIn(0, FOCUS_MAX_STEPS)
        absFocusPosition.value = currentPosition.toDouble()
        absFocusPosition.state = PropertyState.Ok
        relFocusPosition.state = PropertyState.Ok
    }

    private fun handleMotionDirection(elements: Map<String, String>) {
        for ((optName, value) in elements) {
            if (optName == "FOCUS_INWARD" || optName == "FOCUS_OUTWARD") {
                val isOn = value.equals("On", ignoreCase = true)
                focusMotion.options[optName] = isOn
                if (isOn) {
                    focusDirection = optName
                }
            }
        }
        focusMotion.state = PropertyState.Ok
    }

    private fun handleFocusMax(elements: Map<String, String>) {
        val newMax = elements["FOCUS_MAX_VALUE"]?.toDoubleOrNull() ?: return
        focusMax.value = newMax
        focusMax.state = PropertyState.Ok
    }

    // --- Lens management ---

    /**
     * Switch to a new active lens. Per D-14, position resets to 0 on lens change.
     *
     * @param newLensInfo the new lens info, or null to disconnect the focuser
     */
    fun switchActiveLens(newLensInfo: LensInfo?) {
        activeLensInfo = newLensInfo
        currentPosition = 0  // Per D-14: reset on lens switch
        absFocusPosition.value = 0.0
        absFocusPosition.state = PropertyState.Idle
    }

    /**
     * Convert current focus position to diopters for Camera2 focus distance.
     *
     * Step 0 = 0.0 diopters = infinity focus.
     * Step 1000 = minFocusDistance diopters = closest focus.
     * Per pitfall 4 from RESEARCH.md.
     *
     * @return diopter value for Camera2 LENS_FOCUS_DISTANCE, or 0.0 if no lens
     */
    fun currentDiopters(): Float {
        val minFocus = activeLensInfo?.minFocusDistance ?: return 0f
        return (currentPosition.toFloat() / FOCUS_MAX_STEPS) * minFocus
    }

    private fun findProperty(name: String): IndiProperty? = _properties.find { it.name == name }
}
