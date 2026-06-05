package com.miniloc.app

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import java.time.Instant

const val MINILOC_PORT = 47654
const val APP_VERSION = "0.1.1"

data class HostPairing(
    val hostId: String,
    val hostName: String,
    val pairingToken: String,
    val issuedAt: String,
    val expiresInSeconds: Long,
    val minilocPort: Int = MINILOC_PORT,
) {
    val expiresAtMillis: Long?
        get() = runCatching {
            Instant.parse(issuedAt).toEpochMilli() + expiresInSeconds * 1000L
        }.getOrNull()

    fun isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val expiresAt = expiresAtMillis ?: return false
        return nowMillis > expiresAt
    }
}

data class LocationFix(
    val sequence: Long,
    val phoneWallTimeMs: Long,
    val cacheReceivedWallTimeMs: Long,
    val cacheReceivedElapsedRealtimeNs: Long,
    val elapsedRealtimeNs: Long,
    val sourceBatchSize: Int,
    val sourceBatchIndex: Int,
    val lat: Double,
    val lon: Double,
    val horizontalAccuracyM: Float?,
    val altitudeM: Double?,
    val verticalAccuracyM: Float?,
    val speedMps: Float?,
    val speedAccuracyMps: Float?,
    val bearingDeg: Float?,
    val bearingAccuracyDeg: Float?,
    val provider: String,
    val mock: Boolean,
    val batteryPct: Int?,
) {
    fun ageMs(nowElapsedRealtimeNs: Long = android.os.SystemClock.elapsedRealtimeNanos()): Long {
        return ((nowElapsedRealtimeNs - elapsedRealtimeNs) / 1_000_000L).coerceAtLeast(0L)
    }

    fun deliveryLatencyMs(): Long {
        return ((cacheReceivedElapsedRealtimeNs - elapsedRealtimeNs) / 1_000_000L).coerceAtLeast(0L)
    }
}

fun batteryPct(context: Context): Int? {
    val intent = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?: return null
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level < 0 || scale <= 0) return null
    return ((level * 100f) / scale).toInt().coerceIn(0, 100)
}
