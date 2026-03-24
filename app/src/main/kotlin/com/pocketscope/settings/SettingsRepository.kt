package com.pocketscope.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "pocketscope_settings")

/**
 * DataStore-backed persistence for network security settings.
 *
 * Stores whitelisted IPs (always-allow list) and protocol enable/disable toggles.
 * All values are exposed as reactive [Flow]s for Compose observation.
 */
class SettingsRepository(private val context: Context) {

    companion object {
        val WHITELISTED_IPS = stringSetPreferencesKey("whitelisted_ips")
        val INDI_ENABLED = booleanPreferencesKey("indi_enabled")
        val ALPACA_ENABLED = booleanPreferencesKey("alpaca_enabled")
    }

    /** Set of IPs that bypass the approval prompt. */
    val whitelistedIps: Flow<Set<String>> = context.dataStore.data
        .map { prefs -> prefs[WHITELISTED_IPS] ?: emptySet() }

    /** Whether the INDI protocol server is enabled. Defaults to true. */
    val isIndiEnabled: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[INDI_ENABLED] ?: true }

    /** Whether the ASCOM Alpaca protocol server is enabled. Defaults to false (not yet built). */
    val isAlpacaEnabled: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[ALPACA_ENABLED] ?: false }

    /** Add an IP to the always-allow whitelist. */
    suspend fun addWhitelistedIp(ip: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[WHITELISTED_IPS] ?: emptySet()
            prefs[WHITELISTED_IPS] = current + ip
        }
    }

    /** Remove an IP from the always-allow whitelist. */
    suspend fun removeWhitelistedIp(ip: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[WHITELISTED_IPS] ?: emptySet()
            prefs[WHITELISTED_IPS] = current - ip
        }
    }

    /** Enable or disable the INDI protocol server. */
    suspend fun setIndiEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[INDI_ENABLED] = enabled
        }
    }

    /** Enable or disable the ASCOM Alpaca protocol server. */
    suspend fun setAlpacaEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[ALPACA_ENABLED] = enabled
        }
    }
}
