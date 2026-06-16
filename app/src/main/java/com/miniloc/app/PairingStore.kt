package com.miniloc.app

import android.content.Context
import org.json.JSONObject
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

    fun instanceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_INSTANCE_ID, null)?.let { return it }
        val id = "miniloc-${randomCode(4)}"
        prefs.edit().putString(KEY_INSTANCE_ID, id).apply()
        return id
    }

    @Throws(IllegalArgumentException::class)
    fun parsePayload(rawPayload: String): HostPairing {
        val json = runCatching { JSONObject(rawPayload.trim()) }
            .getOrElse { throw IllegalArgumentException("Pairing payload is not valid JSON.") }

        val type = json.optString("type")
        if (type != "miniloc_pair") {
            throw IllegalArgumentException("Pairing payload type must be miniloc_pair.")
        }

        val version = json.optInt("version", -1)
        if (version != 1) {
            throw IllegalArgumentException("Unsupported pairing payload version: $version.")
        }

        val hostId = json.requiredString("host_id")
        val hostName = json.requiredString("host_name")
        val token = json.requiredString("pairing_token")
        val issuedAt = json.requiredString("issued_at")
        val expiresInSeconds = json.optLong("expires_in_s", 0L)
        if (expiresInSeconds <= 0L) {
            throw IllegalArgumentException("expires_in_s must be greater than zero.")
        }

        val issuedAtMillis = runCatching { Instant.parse(issuedAt).toEpochMilli() }
            .getOrElse { throw IllegalArgumentException("issued_at must be an ISO-8601 UTC timestamp.") }
        val expiresAtMillis = issuedAtMillis + expiresInSeconds * 1000L
        if (System.currentTimeMillis() > expiresAtMillis) {
            throw IllegalArgumentException("Pairing payload has expired.")
        }

        val port = json.optInt("miniloc_port", MINILOC_PORT)
        if (port !in 1..65535) {
            throw IllegalArgumentException("miniloc_port must be between 1 and 65535.")
        }

        return HostPairing(hostId, hostName, token, issuedAt, expiresInSeconds, port)
    }

    private fun JSONObject.requiredString(key: String): String {
        val value = optString(key).trim()
        if (value.isEmpty()) {
            throw IllegalArgumentException("$key is required.")
        }
        return value
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
