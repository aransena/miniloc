package com.miniloc.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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
                    else authenticated(session) { pairing -> status(pairing) }
                }
                "/loc_ping" -> {
                    if (session.method != Method.GET) methodNotAllowed()
                    else authenticated(session) { pairing ->
                        json(Response.Status.OK, LocationCache.toLocationJson(pairing.hostId, appContext))
                    }
                }
                "/loc_history" -> {
                    if (session.method != Method.GET) methodNotAllowed()
                    else authenticated(session) { pairing ->
                        json(Response.Status.OK, LocationCache.toHistoryJson(pairing.hostId))
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
        return json(
            Response.Status.OK,
            JSONObject()
                .put("type", "id_pong")
                .put("app", "miniloc")
                .put("version", APP_VERSION)
                .put("instance_id", PairingStore.instanceId(appContext))
                .put("paired_host_id", pairing?.hostId ?: JSONObject.NULL)
                .put("auth_required", true)
                .put("port", pairing?.minilocPort ?: MINILOC_PORT),
        )
    }

    private fun status(pairing: HostPairing): Response {
        val latest = LocationCache.get()
        return json(
            Response.Status.OK,
            JSONObject()
                .put("type", "status")
                .put("ok", true)
                .put("app", "miniloc")
                .put("version", APP_VERSION)
                .put("instance_id", PairingStore.instanceId(appContext))
                .put("paired_host_id", pairing.hostId)
                .put("port", pairing.minilocPort)
                .put("foreground_service_active", LocationForegroundService.isRunning())
                .put("http_server_active", true)
                .put("location_permission", permissionState(Manifest.permission.ACCESS_FINE_LOCATION))
                .put("notification_permission", notificationPermissionState())
                .put("last_fix_sequence", latest?.sequence ?: JSONObject.NULL)
                .put("last_fix_age_ms", latest?.ageMs() ?: JSONObject.NULL)
                .put("last_fix_accuracy_m", latest?.horizontalAccuracyM ?: JSONObject.NULL)
                .put("recent_fix_count", LocationCache.recent().size)
                .put("battery_pct", batteryPct(appContext) ?: JSONObject.NULL),
        )
    }

    private fun authenticated(session: IHTTPSession, block: (HostPairing) -> Response): Response {
        val pairing = PairingStore.get(appContext)
            ?: run {
                clearPairingAndStopSharing()
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

    private fun notificationPermissionState(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return "not_required"
        return permissionState(Manifest.permission.POST_NOTIFICATIONS)
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
