package com.pocketscope.indi.device

import com.pocketscope.indi.properties.IndiProperty

/**
 * Common interface for all INDI devices.
 *
 * Each device exposes a name, a list of INDI properties, and a handler
 * for incoming property-change commands from clients.
 */
interface IndiDevice {
    val deviceName: String
    val properties: List<IndiProperty>

    /**
     * Handles an incoming newXxxVector command for the given property.
     *
     * @param propertyName the INDI property name (e.g., "CCD_EXPOSURE", "CONNECTION")
     * @param elements map of child element name to value string (e.g., "CCD_EXPOSURE_VALUE" -> "5.0")
     */
    fun handleNewProperty(propertyName: String, elements: Map<String, String>)
}
