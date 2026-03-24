package com.pocketscope.network

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.pocketscope.settings.SettingsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApprovalManagerTest {

    private lateinit var settingsRepo: SettingsRepository
    private lateinit var approvalManager: ApprovalManager

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        settingsRepo = SettingsRepository(context)
        approvalManager = ApprovalManager(settingsRepo)
    }

    @Test
    fun `whitelisted IP returns true immediately without pending request`() = runBlocking {
        settingsRepo.addWhitelistedIp("192.168.1.10")
        val result = approvalManager.awaitApproval("192.168.1.10")
        assertTrue(result)
        assertNull(approvalManager.pendingApproval.value)
    }

    @Test
    fun `non-whitelisted IP emits ApprovalRequest on pendingApproval`() = runBlocking {
        val deferred = async { approvalManager.awaitApproval("192.168.1.99") }

        // Wait for the coroutine to reach suspension on the deferred
        waitForPending()

        val pending = approvalManager.pendingApproval.value
        assertNotNull(pending)
        assertEquals("192.168.1.99", pending!!.ip)

        approvalManager.respondToApproval("192.168.1.99", approved = false)
        assertFalse(deferred.await())
    }

    @Test
    fun `respondToApproval with approved true resolves with true`() = runBlocking {
        val deferred = async { approvalManager.awaitApproval("192.168.1.50") }

        waitForPending()

        approvalManager.respondToApproval("192.168.1.50", approved = true)
        assertTrue(deferred.await())
    }

    @Test
    fun `respondToApproval with approved true and alwaysAllow persists to whitelist`() = runBlocking {
        val deferred = async { approvalManager.awaitApproval("192.168.1.60") }

        waitForPending()

        approvalManager.respondToApproval("192.168.1.60", approved = true, alwaysAllow = true)
        assertTrue(deferred.await())

        val whitelisted = settingsRepo.whitelistedIps.first()
        assertTrue(whitelisted.contains("192.168.1.60"))
    }

    @Test
    fun `respondToApproval with approved false resolves with false`() = runBlocking {
        val deferred = async { approvalManager.awaitApproval("192.168.1.70") }

        waitForPending()

        approvalManager.respondToApproval("192.168.1.70", approved = false)
        assertFalse(deferred.await())
    }

    @Test
    fun `awaitApproval times out after 60 seconds and returns false`() = runTest {
        val deferred = async { approvalManager.awaitApproval("192.168.1.80") }
        testScheduler.advanceUntilIdle()
        // DataStore IO needs real time
        Thread.sleep(200)
        testScheduler.advanceUntilIdle()

        // Advance past 60 second timeout
        advanceTimeBy(60_001)
        testScheduler.advanceUntilIdle()

        assertFalse(deferred.await())
    }

    @Test
    fun `duplicate awaitApproval for same IP returns false immediately`() = runBlocking {
        val deferred = async { approvalManager.awaitApproval("192.168.1.90") }

        waitForPending()

        // Second call for same IP while first is pending
        val second = approvalManager.awaitApproval("192.168.1.90")
        assertFalse(second)

        // Clean up first
        approvalManager.respondToApproval("192.168.1.90", approved = false)
        assertFalse(deferred.await())
    }

    @Test
    fun `after approval resolves pendingApproval emits null`() = runBlocking {
        val deferred = async { approvalManager.awaitApproval("192.168.1.100") }

        waitForPending()

        assertNotNull(approvalManager.pendingApproval.value)

        approvalManager.respondToApproval("192.168.1.100", approved = true)
        deferred.await()

        assertNull(approvalManager.pendingApproval.value)
    }

    /**
     * Waits for the approval manager to have a pending request.
     * DataStore reads happen on Dispatchers.IO, so we need real time to elapse.
     */
    private suspend fun waitForPending() {
        withTimeout(5_000) {
            while (approvalManager.pendingApproval.value == null) {
                delay(10)
            }
        }
    }
}
