package com.pocketscope.device

import com.pocketscope.camera.CameraSessionContract
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

class DeviceRegistryTest {

    private lateinit var registry: DeviceRegistry
    private lateinit var mockSessionManager: CameraSessionContract
    private lateinit var mockCaptureDevice: CaptureDevice
    private lateinit var mockFocuserDevice: FocuserDevice

    @Before
    fun setup() {
        mockSessionManager = mock(CameraSessionContract::class.java)
        mockCaptureDevice = mock(CaptureDevice::class.java)
        mockFocuserDevice = mock(FocuserDevice::class.java)

        registry = DeviceRegistry(
            captureDevices = listOf(mockCaptureDevice),
            focuserDevice = mockFocuserDevice,
            sessionManager = mockSessionManager
        )
    }

    @Test
    fun `captureDevices returns provided list`() {
        assertEquals(1, registry.captureDevices.size)
        assertEquals(mockCaptureDevice, registry.captureDevices[0])
    }

    @Test
    fun `focuserDevice returns provided device`() {
        assertEquals(mockFocuserDevice, registry.focuserDevice)
    }

    @Test
    fun `closeAll delegates to sessionManager`() {
        registry.closeAll()
        verify(mockSessionManager).closeAll()
    }
}
