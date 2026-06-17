package com.miniloc.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class LocationForegroundService : Service() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var httpServer: MinilocHttpServer? = null
    private var nsdAdvertiser: MinilocNsdAdvertiser? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val expiryStopRunnable = Runnable {
        if (PairingStore.get(this) == null) {
            LocationCache.clear()
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val pairing = PairingStore.get(this)
        val openLan = PairingStore.isOpenLanEnabled(this)
        if (pairing == null && !openLan) {
            stopSelf()
            return START_NOT_STICKY
        }

        return try {
            val mode = if (openLan) SharingMode.OPEN_LAN else SharingMode.PAIRED
            val port = pairing?.minilocPort ?: MINILOC_PORT
            startForegroundNotification(pairing, mode)
            running.set(true)
            startHttpServer(port)
            startNsdAdvertisement(mode, port)
            startLocationUpdates()
            if (pairing != null) {
                scheduleExpiryStop(pairing)
            } else {
                mainHandler.removeCallbacks(expiryStopRunnable)
            }
            START_STICKY
        } catch (error: Exception) {
            running.set(false)
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = null
        mainHandler.removeCallbacks(expiryStopRunnable)
        nsdAdvertiser?.stop()
        nsdAdvertiser = null
        httpServer?.stop()
        httpServer = null
        running.set(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundNotification(pairing: HostPairing?, mode: SharingMode) {
        createNotificationChannel()
        val notification = buildNotification(pairing, mode)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(pairing: HostPairing?, mode: SharingMode): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LocationForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when (mode) {
            SharingMode.OPEN_LAN -> "Open LAN location sharing is active"
            SharingMode.PAIRED -> "Sharing location with ${pairing?.hostName ?: "paired host"}"
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("miniloc active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop sharing", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "miniloc location",
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = "Location sharing status"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun scheduleExpiryStop(pairing: HostPairing) {
        mainHandler.removeCallbacks(expiryStopRunnable)
        val expiresAtMillis = pairing.expiresAtMillis ?: return
        val delayMs = (expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        mainHandler.postDelayed(expiryStopRunnable, delayMs + 100L)
    }

    @Throws(IOException::class)
    private fun startHttpServer(port: Int) {
        if (httpServer != null) return
        httpServer = MinilocHttpServer(applicationContext, port).also {
            it.start(NanoHTTPD_SOCKET_READ_TIMEOUT_MS, false)
        }
    }

    private fun startNsdAdvertisement(mode: SharingMode, port: Int) {
        val advertiser = nsdAdvertiser ?: MinilocNsdAdvertiser(applicationContext).also {
            nsdAdvertiser = it
        }
        advertiser.start(mode, port)
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasLocationPermission()) {
            throw SecurityException("Location permission is not granted.")
        }
        if (locationCallback != null) return

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS)
            .setMinUpdateIntervalMillis(LOCATION_MIN_UPDATE_INTERVAL_MS)
            .setMaxUpdateDelayMillis(LOCATION_MAX_UPDATE_DELAY_MS)
            .setMinUpdateDistanceMeters(0f)
            .setWaitForAccurateLocation(false)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val locations = result.locations.sortedBy { it.elapsedRealtimeNanos }
                locations.forEachIndexed { index, location ->
                    LocationCache.update(
                        applicationContext,
                        location,
                        sourceBatchSize = locations.size,
                        sourceBatchIndex = index,
                    )
                }
            }
        }
        locationCallback = callback

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) LocationCache.update(applicationContext, location)
        }
        fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val ACTION_START = "com.miniloc.app.START_LOCATION_SHARING"
        const val ACTION_STOP = "com.miniloc.app.STOP_LOCATION_SHARING"
        private const val CHANNEL_ID = "miniloc_location"
        private const val NOTIFICATION_ID = 47654
        private const val NanoHTTPD_SOCKET_READ_TIMEOUT_MS = 5_000
        private const val LOCATION_INTERVAL_MS = 200L
        private const val LOCATION_MIN_UPDATE_INTERVAL_MS = 200L
        private const val LOCATION_MAX_UPDATE_DELAY_MS = 250L
        private val running = AtomicBoolean(false)

        fun isRunning(): Boolean = running.get()
    }
}
