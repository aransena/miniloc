package com.miniloc.app

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Build
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

object LocationCache {
    private const val RECENT_FIX_LIMIT = 10
    private val latestFix = AtomicReference<LocationFix?>()
    private val nextSequence = AtomicLong(1L)
    private val recentFixes = ArrayDeque<LocationFix>(RECENT_FIX_LIMIT)
    private val recentFixesLock = Any()

    @SuppressLint("NewApi")
    fun update(
        context: Context,
        location: Location,
        sourceBatchSize: Int = 1,
        sourceBatchIndex: Int = 0,
    ) {
        val elapsedRealtimeNs = location.elapsedRealtimeNanos
        synchronized(recentFixesLock) {
            val current = latestFix.get()
            if (current != null && elapsedRealtimeNs <= current.elapsedRealtimeNs) {
                return
            }

            val fix = LocationFix(
                sequence = nextSequence.getAndIncrement(),
                phoneWallTimeMs = if (location.time > 0L) location.time else System.currentTimeMillis(),
                cacheReceivedWallTimeMs = System.currentTimeMillis(),
                cacheReceivedElapsedRealtimeNs = SystemClock.elapsedRealtimeNanos(),
                elapsedRealtimeNs = elapsedRealtimeNs,
                sourceBatchSize = sourceBatchSize,
                sourceBatchIndex = sourceBatchIndex,
                lat = location.latitude,
                lon = location.longitude,
                horizontalAccuracyM = if (location.hasAccuracy()) location.accuracy else null,
                altitudeM = if (location.hasAltitude()) location.altitude else null,
                verticalAccuracyM = if (location.hasVerticalAccuracy()) {
                    location.verticalAccuracyMeters
                } else {
                    null
                },
                speedMps = if (location.hasSpeed()) location.speed else null,
                speedAccuracyMps = if (location.hasSpeedAccuracy()) {
                    location.speedAccuracyMetersPerSecond
                } else {
                    null
                },
                bearingDeg = if (location.hasBearing()) location.bearing else null,
                bearingAccuracyDeg = if (location.hasBearingAccuracy()) {
                    location.bearingAccuracyDegrees
                } else {
                    null
                },
                provider = location.provider ?: "fused",
                mock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock else location.isFromMockProvider,
                batteryPct = batteryPct(context),
            )
            latestFix.set(fix)
            recentFixes.addLast(fix)
            while (recentFixes.size > RECENT_FIX_LIMIT) {
                recentFixes.removeFirst()
            }
        }
    }

    fun get(): LocationFix? = latestFix.get()

    fun recent(): List<LocationFix> {
        return synchronized(recentFixesLock) {
            recentFixes.toList()
        }
    }

    fun clear() {
        latestFix.set(null)
        nextSequence.set(1L)
        synchronized(recentFixesLock) {
            recentFixes.clear()
        }
    }

    fun toLocationJson(hostId: String, context: Context, staleAfterMs: Long = 10_000L): JSONObject {
        val fix = latestFix.get()
        if (fix == null) {
            return JSONObject()
                .put("type", "loc_pong")
                .put("ok", false)
                .put("fix_status", "no_fix")
                .put("recent_fixes", recentFixesJson())
        }

        val ageMs = fix.ageMs()
        if (ageMs > staleAfterMs) {
            return JSONObject()
                .put("type", "loc_pong")
                .put("ok", false)
                .put("fix_status", "stale")
                .put("last_fix_age_ms", ageMs)
                .put("last_fix_accuracy_m", fix.horizontalAccuracyM ?: JSONObject.NULL)
                .put("recent_fixes", recentFixesJson())
        }

        return JSONObject()
            .put("type", "loc_pong")
            .put("ok", true)
            .put("host_id", hostId)
            .put("fix_sequence", fix.sequence)
            .put("phone_wall_time_ms", fix.phoneWallTimeMs)
            .put("cache_received_wall_time_ms", fix.cacheReceivedWallTimeMs)
            .put("cache_received_elapsed_realtime_ns", fix.cacheReceivedElapsedRealtimeNs)
            .put("elapsed_realtime_ns", fix.elapsedRealtimeNs)
            .put("age_ms", ageMs)
            .put("delivery_latency_ms", fix.deliveryLatencyMs())
            .put("source_batch_size", fix.sourceBatchSize)
            .put("source_batch_index", fix.sourceBatchIndex)
            .put("lat", fix.lat)
            .put("lon", fix.lon)
            .put("horizontal_accuracy_m", fix.horizontalAccuracyM ?: JSONObject.NULL)
            .put("altitude_m", fix.altitudeM ?: JSONObject.NULL)
            .put("vertical_accuracy_m", fix.verticalAccuracyM ?: JSONObject.NULL)
            .put("speed_mps", fix.speedMps ?: JSONObject.NULL)
            .put("speed_accuracy_mps", fix.speedAccuracyMps ?: JSONObject.NULL)
            .put("bearing_deg", fix.bearingDeg ?: JSONObject.NULL)
            .put("bearing_accuracy_deg", fix.bearingAccuracyDeg ?: JSONObject.NULL)
            .put("provider", fix.provider)
            .put("mock", fix.mock)
            .put("battery_pct", fix.batteryPct ?: batteryPct(context) ?: JSONObject.NULL)
            .put("recent_fixes", recentFixesJson())
    }

    fun toHistoryJson(hostId: String): JSONObject {
        val fixes = recent()
        return JSONObject()
            .put("type", "loc_history")
            .put("ok", true)
            .put("host_id", hostId)
            .put("count", fixes.size)
            .put("max_count", RECENT_FIX_LIMIT)
            .put("fixes", fixesJson(fixes))
    }

    private fun recentFixesJson(): JSONArray {
        return fixesJson(recent())
    }

    private fun fixesJson(fixes: List<LocationFix>): JSONArray {
        val array = JSONArray()
        fixes.forEach { fix -> array.put(fix.toJson()) }
        return array
    }

    private fun LocationFix.toJson(): JSONObject {
        return JSONObject()
            .put("fix_sequence", sequence)
            .put("phone_wall_time_ms", phoneWallTimeMs)
            .put("cache_received_wall_time_ms", cacheReceivedWallTimeMs)
            .put("cache_received_elapsed_realtime_ns", cacheReceivedElapsedRealtimeNs)
            .put("elapsed_realtime_ns", elapsedRealtimeNs)
            .put("age_ms", ageMs())
            .put("delivery_latency_ms", deliveryLatencyMs())
            .put("source_batch_size", sourceBatchSize)
            .put("source_batch_index", sourceBatchIndex)
            .put("lat", lat)
            .put("lon", lon)
            .put("horizontal_accuracy_m", horizontalAccuracyM ?: JSONObject.NULL)
            .put("altitude_m", altitudeM ?: JSONObject.NULL)
            .put("vertical_accuracy_m", verticalAccuracyM ?: JSONObject.NULL)
            .put("speed_mps", speedMps ?: JSONObject.NULL)
            .put("speed_accuracy_mps", speedAccuracyMps ?: JSONObject.NULL)
            .put("bearing_deg", bearingDeg ?: JSONObject.NULL)
            .put("bearing_accuracy_deg", bearingAccuracyDeg ?: JSONObject.NULL)
            .put("provider", provider)
            .put("mock", mock)
            .put("battery_pct", batteryPct ?: JSONObject.NULL)
    }
}
