package com.pocketscope.indi.device

import com.pocketscope.camera.LensInfo
import com.pocketscope.device.FocuserDevice
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
class IndiFocuserDevice(
    private val focuserDevice: FocuserDevice
) : IndiDevice {

    override val deviceName = "PocketScope Focuser"

    private val _properties = mutableListOf<IndiProperty>()
    override val properties: List<IndiProperty> get() = _properties

    private var focusDirection: String = "FOCUS_OUTWARD"  // default direction

    // --- INDI properties ---

    private val connectionSwitch = SwitchProperty(
        device = deviceName,
        name = "CONNECTION",
        label = "Connection",
        group = "Main Control",
        initialState = PropertyState.Ok,
        rule = "OneOfMany",
        options = mutableMapOf("CONNECT" to true, "DISCONNECT" to false),
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
        max = focuserDevice.maxSteps.toDouble(),
        step = 1.0,
        perm = "rw",
        elementName = "FOCUS_ABSOLUTE_POSITION"
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
        max = focuserDevice.maxSteps.toDouble(),
        step = 1.0,
        perm = "rw",
        elementName = "FOCUS_RELATIVE_POSITION"
    )

    private val focusMax = NumberProperty(
        device = deviceName,
        name = "FOCUS_MAX",
        label = "Max Travel",
        group = "Main Control",
        initialState = PropertyState.Idle,
        format = "%.f",
        value = focuserDevice.maxSteps.toDouble(),
        min = 1000.0,
        max = 1000000.0,
        step = 10000.0,
        perm = "rw",
        elementName = "FOCUS_MAX_VALUE"
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
        _properties.add(TextVectorProperty(
            device = deviceName,
            name = "DRIVER_INFO",
            label = "Driver Info",
            group = "General Info",
            initialState = PropertyState.Idle,
            perm = "ro",
            elements = listOf(
                TextElement("DRIVER_NAME", "Name", deviceName),
                TextElement("DRIVER_EXEC", "Exec", "pocketscope"),
                TextElement("DRIVER_VERSION", "Version", "1.0"),
                TextElement("DRIVER_INTERFACE", "Interface", "8")  // FOCUSER_INTERFACE
            )
        ))
        _properties.add(absFocusPosition)
        _properties.add(relFocusPosition)
        _properties.add(focusMax)
        _properties.add(focusMotion)
    }

    // --- Command handling ---

    override fun handleNewProperty(propertyName: String, elements: Map<String, String>) {
        // D-16 revised: Focuser works independently of CCD/lens state.
        // Position tracking works without a lens; currentDiopters() returns 0f when no lens is set.
        when (propertyName) {
            "CONNECTION" -> handleConnection(elements)
            "ABS_FOCUS_POSITION" -> handleAbsPosition(elements)
            "REL_FOCUS_POSITION" -> handleRelPosition(elements)
            "FOCUS_MOTION" -> handleMotionDirection(elements)
            "FOCUS_MAX" -> handleFocusMax(elements)
        }
    }

    private fun handleConnection(elements: Map<String, String>) {
        val connectOn = elements["CONNECT"]?.equals("On", ignoreCase = true) == true
        if (connectOn) {
            connectionSwitch.options["CONNECT"] = true
            connectionSwitch.options["DISCONNECT"] = false
            connectionSwitch.state = PropertyState.Ok
        } else {
            connectionSwitch.options["CONNECT"] = false
            connectionSwitch.options["DISCONNECT"] = true
            connectionSwitch.state = PropertyState.Idle
        }
    }

    private fun handleAbsPosition(elements: Map<String, String>) {
        val requested = elements["FOCUS_ABSOLUTE_POSITION"]?.toDoubleOrNull()?.toInt() ?: return
        absFocusPosition.state = PropertyState.Busy
        val clamped = focuserDevice.moveAbsolute(requested)
        absFocusPosition.value = clamped.toDouble()
        absFocusPosition.state = PropertyState.Ok
    }

    private fun handleRelPosition(elements: Map<String, String>) {
        val steps = elements["FOCUS_RELATIVE_POSITION"]?.toDoubleOrNull()?.toInt() ?: return
        val outward = focusDirection == "FOCUS_OUTWARD"
        absFocusPosition.state = PropertyState.Busy
        relFocusPosition.state = PropertyState.Busy
        val newPos = focuserDevice.moveRelative(steps, outward)
        absFocusPosition.value = newPos.toDouble()
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
        focuserDevice.switchActiveLens(newLensInfo)
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
    fun currentDiopters(): Float = focuserDevice.currentDiopters()

    private fun findProperty(name: String): IndiProperty? = _properties.find { it.name == name }
}
