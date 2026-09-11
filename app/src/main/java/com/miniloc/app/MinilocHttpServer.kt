package com.miniloc.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.util.Locale

class MinilocHttpServer(
    private val appContext: Context,
    port: Int,
) : NanoHTTPD("0.0.0.0", port) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            val path = session.uri.substringBefore("?")
            when (path) {
                "/id_ping" -> {
                    if (session.method != Method.GET) methodNotAllowed()
                    else idPing()
                }
                "/status" -> {
                    if (session.method != Method.GET) methodNotAllowed()
                    else readable(session) { access -> status(access) }
                }
                "/loc_ping" -> {
                    if (session.method != Method.GET) methodNotAllowed()
                    else readable(session) { access ->
                        json(
                            Response.Status.OK,
                            LocationCache.toLocationJson(
                                access.mode,
                                access.pairing?.hostId,
                                PairingStore.instanceId(appContext),
                                appContext,
                            ),
                        )
                    }
                }
                "/loc_history" -> {
                    if (session.method != Method.GET) methodNotAllowed()
                    else readable(session) { access ->
                        json(
                            Response.Status.OK,
                            LocationCache.toHistoryJson(
                                access.mode,
                                access.pairing?.hostId,
                                PairingStore.instanceId(appContext),
                            ),
                        )
                    }
                }
                "/forget_pairing" -> {
                    if (session.method != Method.POST) methodNotAllowed()
                    else authenticated(session) {
                        clearPairingAndStopSharing()
                        json(Response.Status.OK, JSONObject().put("ok", true).put("pairing_cleared", true))
                    }
                }
                else -> json(Response.Status.NOT_FOUND, JSONObject().put("ok", false).put("error", "not_found"))
            }
        } catch (error: Exception) {
            json(
                Response.Status.INTERNAL_ERROR,
                JSONObject().put("ok", false).put("error", error.message ?: "internal_error"),
            )
        }
    }

    private fun idPing(): Response {
        val pairing = PairingStore.get(appContext)
        val openLan = PairingStore.isOpenLanEnabled(appContext)
        return json(
            Response.Status.OK,
            JSONObject()
                .put("type", "id_pong")
                .put("app", "miniloc")
                .put("version", APP_VERSION)
                .put("instance_id", PairingStore.instanceId(appContext))
                .put("access_mode", if (openLan) SharingMode.OPEN_LAN.wireName else SharingMode.PAIRED.wireName)
                .put("paired_host_id", pairing?.hostId ?: JSONObject.NULL)
                .put("auth_required", !openLan)
                .put("read_auth_required", !openLan)
                .put("port", pairing?.minilocPort ?: MINILOC_PORT),
        )
    }

    private fun status(access: ReadAccess): Response {
        val latest = LocationCache.get()
        val lastNetworkRefreshElapsedMs = LocationForegroundService.lastNetworkPublishElapsedMs()
        return json(
            Response.Status.OK,
            JSONObject()
                .put("type", "status")
                .put("ok", true)
                .put("app", "miniloc")
                .put("version", APP_VERSION)
                .put("instance_id", PairingStore.instanceId(appContext))
                .put("access_mode", access.mode.wireName)
                .put("paired_host_id", access.pairing?.hostId ?: JSONObject.NULL)
                .put("auth_required", access.mode != SharingMode.OPEN_LAN)
                .put("read_auth_required", access.mode != SharingMode.OPEN_LAN)
                .put("port", access.pairing?.minilocPort ?: MINILOC_PORT)
                .put("discovery_service_type", MINILOC_NSD_SERVICE_TYPE)
                .put("discovery_service_name", PairingStore.serviceName(appContext))
                .put("foreground_service_active", LocationForegroundService.isRunning())
                .put("http_server_active", true)
                .put("location_permission", locationPermissionState())
                .put("background_location_permission", backgroundLocationPermissionState())
                .put("notification_permission", notificationPermissionState())
                .put("location_update_error", LocationForegroundService.locationUpdateError() ?: JSONObject.NULL)
                .put("availability_wake_lock_active", LocationForegroundService.isWakeLockHeld())
                .put("availability_wifi_lock_active", LocationForegroundService.isWifiLockHeld())
                .put("availability_multicast_lock_active", LocationForegroundService.isMulticastLockHeld())
                .put("availability_lock_error", LocationForegroundService.availabilityLockError() ?: JSONObject.NULL)
                .put("network_publish_error", LocationForegroundService.networkPublishError() ?: JSONObject.NULL)
                .put(
                    "network_last_refresh_age_ms",
                    lastNetworkRefreshElapsedMs?.let { (SystemClock.elapsedRealtime() - it).coerceAtLeast(0L) }
                        ?: JSONObject.NULL,
                )
                .put("battery_optimization_ignored", batteryOptimizationIgnored() ?: JSONObject.NULL)
                .put("power_save_mode_active", powerSaveModeActive() ?: JSONObject.NULL)
                .put("last_fix_sequence", latest?.sequence ?: JSONObject.NULL)
                .put("last_fix_age_ms", latest?.ageMs() ?: JSONObject.NULL)
                .put("last_fix_accuracy_m", latest?.horizontalAccuracyM ?: JSONObject.NULL)
                .put("recent_fix_count", LocationCache.recent().size)
                .put("battery_pct", batteryPct(appContext) ?: JSONObject.NULL),
        )
    }

    private data class ReadAccess(
        val mode: SharingMode,
        val pairing: HostPairing?,
    )

    private fun readable(session: IHTTPSession, block: (ReadAccess) -> Response): Response {
        val pairing = PairingStore.get(appContext)
        if (PairingStore.isOpenLanEnabled(appContext)) {
            return block(ReadAccess(SharingMode.OPEN_LAN, pairing))
        }
        if (pairing == null) {
            clearPairingAndStopSharing()
            return unauthorized()
        }
        val hostId = header(session, "X-Miniloc-Host-Id")
        val token = header(session, "X-Miniloc-Token")
        if (hostId != pairing.hostId || token != pairing.pairingToken) {
            return unauthorized()
        }
        return block(ReadAccess(SharingMode.PAIRED, pairing))
    }

    private fun authenticated(session: IHTTPSession, block: (HostPairing) -> Response): Response {
        val pairing = PairingStore.get(appContext)
            ?: run {
                if (!PairingStore.isOpenLanEnabled(appContext)) {
                    clearPairingAndStopSharing()
                }
                return unauthorized()
            }
        val hostId = header(session, "X-Miniloc-Host-Id")
        val token = header(session, "X-Miniloc-Token")
        if (hostId != pairing.hostId || token != pairing.pairingToken) {
            return unauthorized()
        }
        return block(pairing)
    }

    private fun clearPairingAndStopSharing() {
        PairingStore.clear(appContext)
        LocationCache.clear()
        Handler(Looper.getMainLooper()).post {
            appContext.stopService(Intent(appContext, LocationForegroundService::class.java))
        }
    }

    private fun header(session: IHTTPSession, name: String): String? {
        val lowerName = name.lowercase(Locale.US)
        return session.headers[lowerName] ?: session.headers[name]
    }

    private fun permissionState(permission: String): String {
        return if (ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED) {
            "granted"
        } else {
            "denied"
        }
    }

    private fun locationPermissionState(): String {
        val fine = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION)
        return if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            "granted"
        } else {
            "denied"
        }
    }

    private fun backgroundLocationPermissionState(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "not_required"
        return permissionState(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    private fun notificationPermissionState(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return "not_required"
        return permissionState(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun batteryOptimizationIgnored(): Boolean? {
        val powerManager = appContext.getSystemService(PowerManager::class.java) ?: return null
        return powerManager.isIgnoringBatteryOptimizations(appContext.packageName)
    }

    private fun powerSaveModeActive(): Boolean? {
        val powerManager = appContext.getSystemService(PowerManager::class.java) ?: return null
        return powerManager.isPowerSaveMode
    }

    private fun unauthorized(): Response {
        return json(Response.Status.UNAUTHORIZED, JSONObject().put("ok", false).put("error", "unauthorized"))
    }

    private fun methodNotAllowed(): Response {
        return json(Response.Status.METHOD_NOT_ALLOWED, JSONObject().put("ok", false).put("error", "method_not_allowed"))
    }

    private fun json(status: Response.Status, body: JSONObject): Response {
        return newFixedLengthResponse(status, "application/json", body.toString()).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("Access-Control-Allow-Origin", "*")
        }
    }
}
