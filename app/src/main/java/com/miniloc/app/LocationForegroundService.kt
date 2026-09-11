package com.miniloc.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class LocationForegroundService : Service() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var httpServer: MinilocHttpServer? = null
    private var nsdAdvertiser: MinilocNsdAdvertiser? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var activeMode: SharingMode? = null
    private var activePort: Int = MINILOC_PORT
    private var forceHttpRestartOnRefresh = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val expiryStopRunnable = Runnable {
        if (PairingStore.get(this) == null) {
            LocationCache.clear()
            stopSelf()
        }
    }
    private val locationRetryRunnable = Runnable {
        if (running.get()) {
            startLocationUpdatesSafely()
        }
    }
    private val networkRefreshRunnable = Runnable {
        val restartHttp = forceHttpRestartOnRefresh
        forceHttpRestartOnRefresh = false
        refreshNetworkPublishing(restartHttp = restartHttp)
    }
    private val serviceWatchdogRunnable = Runnable {
        if (running.get()) {
            refreshNetworkPublishing(restartHttp = false)
            restartStaleLocationUpdatesIfNeeded()
            scheduleServiceWatchdog()
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
            activeMode = mode
            activePort = port
            startForegroundNotification(pairing, mode)
            running.set(true)
            lastLocationUpdateError.set(null)
            startHttpServer(port)
            startNsdAdvertisement(mode, port)
            acquireAvailabilityLocks()
            lastNetworkPublishError.set(null)
            lastNetworkPublishElapsedMs.set(SystemClock.elapsedRealtime())
            lastLocationRequestElapsedMs.set(0L)
            lastLocationCallbackElapsedMs.set(0L)
            registerNetworkCallback()
            scheduleServiceWatchdog()
            startLocationUpdates()
            if (pairing != null) {
                scheduleExpiryStop(pairing)
            } else {
                mainHandler.removeCallbacks(expiryStopRunnable)
            }
            START_STICKY
        } catch (error: Exception) {
            running.set(false)
            lastLocationUpdateError.set(error.message ?: error::class.java.simpleName)
            Log.w(TAG, "Location foreground service failed to start", error)
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = null
        mainHandler.removeCallbacks(expiryStopRunnable)
        mainHandler.removeCallbacks(locationRetryRunnable)
        mainHandler.removeCallbacks(networkRefreshRunnable)
        mainHandler.removeCallbacks(serviceWatchdogRunnable)
        unregisterNetworkCallback()
        releaseAvailabilityLocks()
        nsdAdvertiser?.stop()
        nsdAdvertiser = null
        httpServer?.stop()
        httpServer = null
        activeMode = null
        running.set(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundNotification(pairing: HostPairing?, mode: SharingMode) {
        createNotificationChannel()
        val notification = buildNotification(pairing, mode)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPES)
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

    private fun restartNsdAdvertisement(mode: SharingMode, port: Int) {
        val advertiser = nsdAdvertiser ?: MinilocNsdAdvertiser(applicationContext).also {
            nsdAdvertiser = it
        }
        advertiser.start(mode, port, force = true)
    }

    private fun restartHttpServer(port: Int) {
        runCatching {
            httpServer?.stop()
        }.onFailure { error ->
            Log.w(TAG, "HTTP server stop before restart failed", error)
        }
        httpServer = null
        startHttpServer(port)
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scheduleNetworkPublishingRefresh(restartHttp = true)
            }

            override fun onLost(network: Network) {
                scheduleNetworkPublishingRefresh(restartHttp = true)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                ) {
                    scheduleNetworkPublishingRefresh(restartHttp = true)
                }
            }
        }
        runCatching {
            manager.registerDefaultNetworkCallback(callback)
            connectivityManager = manager
            networkCallback = callback
        }.onFailure { error ->
            lastNetworkPublishError.set("network_callback: ${error.message ?: error::class.java.simpleName}")
            Log.w(TAG, "Network callback registration failed", error)
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        runCatching {
            connectivityManager?.unregisterNetworkCallback(callback)
        }.onFailure { error ->
            Log.w(TAG, "Network callback unregistration failed", error)
        }
        networkCallback = null
        connectivityManager = null
    }

    private fun scheduleNetworkPublishingRefresh(restartHttp: Boolean) {
        mainHandler.post {
            forceHttpRestartOnRefresh = forceHttpRestartOnRefresh || restartHttp
            mainHandler.removeCallbacks(networkRefreshRunnable)
            mainHandler.postDelayed(networkRefreshRunnable, NETWORK_REFRESH_DELAY_MS)
        }
    }

    private fun refreshNetworkPublishing(restartHttp: Boolean) {
        val mode = activeMode ?: return
        val port = activePort
        runCatching {
            acquireAvailabilityLocks()
            if (restartHttp) {
                restartHttpServer(port)
                restartNsdAdvertisement(mode, port)
            } else {
                startHttpServer(port)
                startNsdAdvertisement(mode, port)
            }
            lastNetworkPublishError.set(null)
            lastNetworkPublishElapsedMs.set(SystemClock.elapsedRealtime())
        }.onFailure { error ->
            lastNetworkPublishError.set(error.message ?: error::class.java.simpleName)
            Log.w(TAG, "Network publisher refresh failed", error)
        }
    }

    private fun scheduleServiceWatchdog() {
        mainHandler.removeCallbacks(serviceWatchdogRunnable)
        mainHandler.postDelayed(serviceWatchdogRunnable, SERVICE_WATCHDOG_INTERVAL_MS)
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireAvailabilityLocks() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "miniloc:sharing",
            ).apply {
                setReferenceCounted(false)
            }
        }
        runCatching {
            wakeLock?.takeUnless { it.isHeld }?.acquire()
        }.onFailure { error ->
            lastAvailabilityLockError.set("wake_lock: ${error.message ?: error::class.java.simpleName}")
            Log.w(TAG, "Wake lock acquisition failed", error)
        }
        updateAvailabilityLockState()

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager == null) {
            lastAvailabilityLockError.set("wifi_manager_unavailable")
            updateAvailabilityLockState()
            return
        }
        if (wifiLock == null) {
            wifiLock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "miniloc:network",
            ).apply {
                setReferenceCounted(false)
            }
        }
        runCatching {
            wifiLock?.takeUnless { it.isHeld }?.acquire()
        }.onFailure { error ->
            lastAvailabilityLockError.set("wifi_lock: ${error.message ?: error::class.java.simpleName}")
            Log.w(TAG, "Wi-Fi lock acquisition failed", error)
        }
        updateAvailabilityLockState()

        if (multicastLock == null) {
            multicastLock = wifiManager.createMulticastLock("miniloc:mdns").apply {
                setReferenceCounted(false)
            }
        }
        runCatching {
            multicastLock?.takeUnless { it.isHeld }?.acquire()
        }.onFailure { error ->
            lastAvailabilityLockError.set("multicast_lock: ${error.message ?: error::class.java.simpleName}")
            Log.w(TAG, "Multicast lock acquisition failed", error)
        }
        updateAvailabilityLockState()

        if (
            wakeLock?.isHeld == true &&
            wifiLock?.isHeld == true &&
            multicastLock?.isHeld == true
        ) {
            lastAvailabilityLockError.set(null)
        }
    }

    private fun releaseAvailabilityLocks() {
        runCatching {
            multicastLock?.takeIf { it.isHeld }?.release()
        }.onFailure { error -> Log.w(TAG, "Multicast lock release failed", error) }
        multicastLock = null

        runCatching {
            wifiLock?.takeIf { it.isHeld }?.release()
        }.onFailure { error -> Log.w(TAG, "Wi-Fi lock release failed", error) }
        wifiLock = null

        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
        }.onFailure { error -> Log.w(TAG, "Wake lock release failed", error) }
        wakeLock = null
        updateAvailabilityLockState()
        lastAvailabilityLockError.set(null)
    }

    private fun updateAvailabilityLockState() {
        wakeLockHeld.set(wakeLock?.isHeld == true)
        wifiLockHeld.set(wifiLock?.isHeld == true)
        multicastLockHeld.set(multicastLock?.isHeld == true)
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasLocationPermission()) {
            throw SecurityException("Foreground location permission is not granted.")
        }
        if (locationCallback != null) return

        lastLocationRequestElapsedMs.set(SystemClock.elapsedRealtime())
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
                if (locations.isNotEmpty()) {
                    lastLocationCallbackElapsedMs.set(SystemClock.elapsedRealtime())
                    lastLocationUpdateError.set(null)
                }
            }
        }
        locationCallback = callback

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) LocationCache.update(applicationContext, location)
        }
        fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnSuccessListener {
                lastLocationUpdateError.set(null)
                mainHandler.removeCallbacks(locationRetryRunnable)
            }
            .addOnFailureListener { error ->
                lastLocationUpdateError.set(error.message ?: error::class.java.simpleName)
                Log.w(TAG, "Location update request failed", error)
                if (locationCallback === callback) {
                    locationCallback = null
                }
                runCatching { fusedLocationClient.removeLocationUpdates(callback) }
                scheduleLocationRetry()
            }
    }

    private fun startLocationUpdatesSafely() {
        runCatching {
            startLocationUpdates()
        }.onFailure { error ->
            lastLocationUpdateError.set(error.message ?: error::class.java.simpleName)
            Log.w(TAG, "Location update retry failed", error)
            if (hasLocationPermission()) {
                scheduleLocationRetry()
            } else {
                stopSelf()
            }
        }
    }

    private fun scheduleLocationRetry() {
        if (!running.get()) return
        mainHandler.removeCallbacks(locationRetryRunnable)
        mainHandler.postDelayed(locationRetryRunnable, LOCATION_RETRY_DELAY_MS)
    }

    private fun restartStaleLocationUpdatesIfNeeded() {
        val callback = locationCallback ?: return
        val nowMs = SystemClock.elapsedRealtime()
        val lastCallbackMs = lastLocationCallbackElapsedMs.get()
        val requestStartedMs = lastLocationRequestElapsedMs.get()
        val referenceMs = if (lastCallbackMs > 0L) lastCallbackMs else requestStartedMs
        if (referenceMs <= 0L || nowMs - referenceMs < LOCATION_CALLBACK_STALE_MS) return

        Log.w(TAG, "Restarting stale location updates after ${nowMs - referenceMs} ms without callbacks")
        locationCallback = null
        runCatching { fusedLocationClient.removeLocationUpdates(callback) }
        startLocationUpdatesSafely()
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val ACTION_START = "com.miniloc.app.START_LOCATION_SHARING"
        const val ACTION_STOP = "com.miniloc.app.STOP_LOCATION_SHARING"
        private const val TAG = "MinilocLocationService"
        private const val CHANNEL_ID = "miniloc_location"
        private const val NOTIFICATION_ID = 47654
        private const val NanoHTTPD_SOCKET_READ_TIMEOUT_MS = 5_000
        private const val LOCATION_INTERVAL_MS = 200L
        private const val LOCATION_MIN_UPDATE_INTERVAL_MS = 200L
        private const val LOCATION_MAX_UPDATE_DELAY_MS = 250L
        private const val LOCATION_RETRY_DELAY_MS = 5_000L
        private const val LOCATION_CALLBACK_STALE_MS = 30_000L
        private const val NETWORK_REFRESH_DELAY_MS = 750L
        private const val SERVICE_WATCHDOG_INTERVAL_MS = 15_000L
        private val FOREGROUND_SERVICE_TYPES =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        private val running = AtomicBoolean(false)
        private val lastLocationUpdateError = AtomicReference<String?>(null)
        private val lastAvailabilityLockError = AtomicReference<String?>(null)
        private val lastNetworkPublishError = AtomicReference<String?>(null)
        private val wakeLockHeld = AtomicBoolean(false)
        private val wifiLockHeld = AtomicBoolean(false)
        private val multicastLockHeld = AtomicBoolean(false)
        private val lastNetworkPublishElapsedMs = AtomicReference<Long?>(null)
        private val lastLocationRequestElapsedMs = AtomicReference(0L)
        private val lastLocationCallbackElapsedMs = AtomicReference(0L)

        fun isRunning(): Boolean = running.get()
        fun locationUpdateError(): String? = lastLocationUpdateError.get()
        fun availabilityLockError(): String? = lastAvailabilityLockError.get()
        fun networkPublishError(): String? = lastNetworkPublishError.get()
        fun lastNetworkPublishElapsedMs(): Long? = lastNetworkPublishElapsedMs.get()
        fun isWakeLockHeld(): Boolean = wakeLockHeld.get()
        fun isWifiLockHeld(): Boolean = wifiLockHeld.get()
        fun isMulticastLockHeld(): Boolean = multicastLockHeld.get()
    }
}
