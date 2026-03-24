package com.pocketscope.network

import com.pocketscope.settings.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * A pending connection approval request displayed to the user.
 */
data class ApprovalRequest(
    val ip: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Manages the suspend-based connection approval flow for incoming network connections.
 *
 * When a non-whitelisted IP connects, [awaitApproval] suspends until the user responds
 * (via [respondToApproval]) or a 60-second timeout elapses. Duplicate requests for the
 * same IP while one is pending are immediately rejected (deduplication).
 *
 * The user can choose "Always Allow" which persists the IP to [SettingsRepository],
 * bypassing the prompt on future connections.
 */
class ApprovalManager(private val settingsRepository: SettingsRepository) {

    companion object {
        /** Set by IndiServerService when the service starts, read by MainActivity for UI responses. */
        var instance: ApprovalManager? = null
    }

    private val _pendingApproval = MutableStateFlow<ApprovalRequest?>(null)

    /** The current pending approval request, or null if none. Observed by UI. */
    val pendingApproval: StateFlow<ApprovalRequest?> = _pendingApproval.asStateFlow()

    private val pendingDeferreds = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /**
     * Suspends until the user approves or denies the connection from [ip],
     * or until the 60-second timeout elapses (returning false).
     *
     * Returns true immediately if [ip] is already whitelisted.
     * Returns false immediately if a request for [ip] is already pending (dedup).
     */
    suspend fun awaitApproval(ip: String): Boolean {
        // Check whitelist first
        val whitelisted = settingsRepository.whitelistedIps.first()
        if (ip in whitelisted) return true

        // Dedup: if already pending for this IP, reject immediately
        if (pendingDeferreds.containsKey(ip)) return false

        val deferred = CompletableDeferred<Boolean>()
        pendingDeferreds[ip] = deferred
        _pendingApproval.value = ApprovalRequest(ip)

        return try {
            withTimeoutOrNull(60_000L) { deferred.await() } ?: false
        } finally {
            pendingDeferreds.remove(ip)
            _pendingApproval.value = null
        }
    }

    /**
     * Responds to a pending approval request for [ip].
     *
     * @param approved Whether to allow the connection.
     * @param alwaysAllow If true and [approved], persists [ip] to the whitelist for future auto-approval.
     */
    suspend fun respondToApproval(ip: String, approved: Boolean, alwaysAllow: Boolean = false) {
        val deferred = pendingDeferreds[ip] ?: return

        if (approved && alwaysAllow) {
            settingsRepository.addWhitelistedIp(ip)
        }

        deferred.complete(approved)
    }
}
