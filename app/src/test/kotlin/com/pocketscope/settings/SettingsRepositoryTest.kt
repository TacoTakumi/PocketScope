package com.pocketscope.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryTest {

    private lateinit var repo: SettingsRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        repo = SettingsRepository(context)
    }

    @Test
    fun `addWhitelistedIp persists and appears in flow`() = runTest {
        repo.addWhitelistedIp("192.168.1.10")
        val ips = repo.whitelistedIps.first()
        assertTrue(ips.contains("192.168.1.10"))
    }

    @Test
    fun `removeWhitelistedIp removes from flow`() = runTest {
        repo.addWhitelistedIp("192.168.1.10")
        repo.removeWhitelistedIp("192.168.1.10")
        val ips = repo.whitelistedIps.first()
        assertFalse(ips.contains("192.168.1.10"))
    }

    @Test
    fun `isIndiEnabled defaults to true`() = runTest {
        assertTrue(repo.isIndiEnabled.first())
    }

    @Test
    fun `isAlpacaEnabled defaults to false`() = runTest {
        assertFalse(repo.isAlpacaEnabled.first())
    }

    @Test
    fun `setIndiEnabled persists false`() = runTest {
        repo.setIndiEnabled(false)
        assertFalse(repo.isIndiEnabled.first())
    }
}
