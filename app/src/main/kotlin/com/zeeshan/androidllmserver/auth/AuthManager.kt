package com.zeeshan.androidllmserver.auth

import android.content.Context
import com.zeeshan.androidllmserver.prefs.ServerPreferences
import java.security.SecureRandom

/**
 * Manages bearer-token authentication for the HTTP server.
 *
 * On first access, a cryptographically random 32-character hex token is
 * generated and persisted in [ServerPreferences]. The token survives app
 * restarts and device reboots. Users can regenerate it at any time from
 * the UI (Phase 8).
 */
class AuthManager(context: Context) {

    private val prefs = ServerPreferences(context)

    /**
     * Returns the existing token or creates one on first call.
     */
    fun getOrCreateToken(): String {
        val existing = prefs.authToken
        if (!existing.isNullOrBlank()) return existing
        return regenerateToken()
    }

    /**
     * Generates a new random token, persists it, and returns it.
     */
    fun regenerateToken(): String {
        val bytes = ByteArray(16) // 16 bytes = 32 hex chars
        SecureRandom().nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        prefs.authToken = token
        return token
    }

    /**
     * Returns `true` if [token] matches the stored bearer token.
     */
    fun validateToken(token: String): Boolean {
        val stored = prefs.authToken ?: return false
        // Constant-time comparison to avoid timing attacks.
        if (token.length != stored.length) return false
        var diff = 0
        for (i in token.indices) {
            diff = diff or (token[i].code xor stored[i].code)
        }
        return diff == 0
    }

    /** Whether authentication is enabled. Delegates to [ServerPreferences]. */
    var authEnabled: Boolean
        get() = prefs.authEnabled
        set(value) { prefs.authEnabled = value }
}
