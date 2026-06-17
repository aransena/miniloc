package com.miniloc.app

import android.content.Context
import java.security.SecureRandom
import java.time.Instant
import java.util.Locale

object PairingStore {
    private const val PREFS = "miniloc_pairing"
    private const val KEY_HOST_ID = "host_id"
    private const val KEY_HOST_NAME = "host_name"
    private const val KEY_PAIRING_TOKEN = "pairing_token"
    private const val KEY_ISSUED_AT = "issued_at"
    private const val KEY_EXPIRES_IN_S = "expires_in_s"
    private const val KEY_MINILOC_PORT = "miniloc_port"
    private const val KEY_INSTANCE_ID = "instance_id"
    private const val KEY_OPEN_LAN_ENABLED = "open_lan_enabled"
    private const val PAIRING_PREFIX = "ML1"
    private const val DEFAULT_PAIRING_EXPIRES_IN_S = 600L
    private val pairingHostIdRegex = Regex("[A-Z0-9][A-Z0-9-]{0,31}")
    private val pairingTokenRegex = Regex("[A-Z2-7]{16,64}")

    fun get(context: Context): HostPairing? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hostId = prefs.getString(KEY_HOST_ID, null) ?: return null
        val hostName = prefs.getString(KEY_HOST_NAME, null) ?: return null
        val token = prefs.getString(KEY_PAIRING_TOKEN, null) ?: return null
        val issuedAt = prefs.getString(KEY_ISSUED_AT, null) ?: return null
        val expiresInSeconds = prefs.getLong(KEY_EXPIRES_IN_S, 0L)
        val port = prefs.getInt(KEY_MINILOC_PORT, MINILOC_PORT)
        val pairing = HostPairing(hostId, hostName, token, issuedAt, expiresInSeconds, port)
        if (pairing.isExpired()) {
            clear(context)
            return null
        }
        return pairing
    }

    fun save(context: Context, pairing: HostPairing) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HOST_ID, pairing.hostId)
            .putString(KEY_HOST_NAME, pairing.hostName)
            .putString(KEY_PAIRING_TOKEN, pairing.pairingToken)
            .putString(KEY_ISSUED_AT, pairing.issuedAt)
            .putLong(KEY_EXPIRES_IN_S, pairing.expiresInSeconds)
            .putInt(KEY_MINILOC_PORT, pairing.minilocPort)
            .putBoolean(KEY_OPEN_LAN_ENABLED, false)
            .apply()
    }

    fun clear(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val instanceId = prefs.getString(KEY_INSTANCE_ID, null)
        prefs.edit().clear().apply()
        if (instanceId != null) {
            prefs.edit().putString(KEY_INSTANCE_ID, instanceId).apply()
        }
    }

    fun isOpenLanEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_OPEN_LAN_ENABLED, false)
    }

    fun enableOpenLan(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val instanceId = prefs.getString(KEY_INSTANCE_ID, null)
        prefs.edit()
            .remove(KEY_HOST_ID)
            .remove(KEY_HOST_NAME)
            .remove(KEY_PAIRING_TOKEN)
            .remove(KEY_ISSUED_AT)
            .remove(KEY_EXPIRES_IN_S)
            .remove(KEY_MINILOC_PORT)
            .putBoolean(KEY_OPEN_LAN_ENABLED, true)
            .apply()
        if (instanceId != null) {
            prefs.edit().putString(KEY_INSTANCE_ID, instanceId).apply()
        }
    }

    fun disableOpenLan(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_OPEN_LAN_ENABLED, false)
            .apply()
    }

    fun hasSharingConfig(context: Context): Boolean {
        return get(context) != null || isOpenLanEnabled(context)
    }

    fun serviceName(context: Context): String = instanceId(context)

    fun instanceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_INSTANCE_ID, null)?.let { return it }
        val id = "miniloc-${randomCode(4)}"
        prefs.edit().putString(KEY_INSTANCE_ID, id).apply()
        return id
    }

    @Throws(IllegalArgumentException::class)
    fun parsePayload(rawPayload: String): HostPairing {
        val parts = rawPayload.trim().split(":")
        if (parts.size != 3 || parts[0] != PAIRING_PREFIX) {
            throw IllegalArgumentException("Pairing QR must use ML1:<host_id>:<token>.")
        }
        val hostId = parts[1].trim()
        val token = parts[2].trim()
        if (hostId.isEmpty()) {
            throw IllegalArgumentException("host_id is required.")
        }
        if (!pairingHostIdRegex.matches(hostId)) {
            throw IllegalArgumentException("host_id must use A-Z, 0-9, or '-' and be at most 32 characters.")
        }
        if (token.isEmpty()) {
            throw IllegalArgumentException("pairing token is required.")
        }
        if (!pairingTokenRegex.matches(token)) {
            throw IllegalArgumentException("pairing token must be uppercase base32 without padding.")
        }
        val issuedAt = Instant.ofEpochSecond(Instant.now().epochSecond).toString()
        return HostPairing(hostId, hostId, token, issuedAt, DEFAULT_PAIRING_EXPIRES_IN_S, MINILOC_PORT)
    }

    private fun randomCode(length: Int): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        return (1..length)
            .map { alphabet[random.nextInt(alphabet.length)] }
            .joinToString("")
            .uppercase(Locale.US)
    }
}
