package com.miniloc.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var cameraExecutor: ExecutorService
    private var pendingPairing: HostPairing? = null
    private var pendingOpenLanStart = false
    private var autoOpenLanAttempted = false
    private var previewView: PreviewView? = null
    private var scannerStatusView: TextView? = null
    private var scannerStatusText: String? = null
    private var camera: Camera? = null
    private var focusRequestSequence = 0
    private val analyzing = AtomicBoolean(false)

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (previewView == null) {
                showCurrentState()
            }
            if (PairingStore.hasSharingConfig(this@MainActivity) && previewView == null) {
                mainHandler.postDelayed(this, ACTIVE_REFRESH_INTERVAL_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        setContentView(
            ScrollView(this).apply {
                addView(
                    root,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            },
        )

        showCurrentState()
    }

    override fun onResume() {
        super.onResume()
        if (previewView == null) {
            mainHandler.post(refreshRunnable)
        }
    }

    override fun onPause() {
        mainHandler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        stopCamera()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CAMERA_PERMISSION -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                    showScanner()
                } else {
                    toast("Camera permission is required to scan a pairing QR.")
                    showOpenLanPermissionRequired()
                }
            }
            REQUEST_SHARING_PERMISSIONS -> {
                val locationGranted = hasLocationPermission()
                val notificationGranted = hasNotificationPermission()
                if (locationGranted && notificationGranted) {
                    when {
                        pendingOpenLanStart -> startOpenLanSharing()
                        pendingPairing != null -> pendingPairing?.let { savePairingAndStart(it) }
                        else -> showUnpaired()
                    }
                } else {
                    toast("Location and notification permissions are required to share location.")
                    when {
                        pendingPairing != null -> pendingPairing?.let { showPairingConfirmation(it) }
                        pendingOpenLanStart -> showOpenLanPermissionRequired()
                        else -> showOpenLanPermissionRequired()
                    }
                }
            }
        }
    }

    private fun showCurrentState() {
        when {
            PairingStore.get(this) != null -> showActive()
            PairingStore.isOpenLanEnabled(this) -> showOpenLanActive()
            else -> startDefaultOpenLan()
        }
    }

    private fun startDefaultOpenLan() {
        if (autoOpenLanAttempted) {
            showOpenLanPermissionRequired()
            return
        }
        autoOpenLanAttempted = true
        showOpenLanStarting()
        ensureOpenLanPermissions()
    }

    private fun showOpenLanStarting() {
        stopCamera()
        pendingPairing = null
        pendingOpenLanStart = true
        resetRoot()
        root.addView(title("miniloc"))
        root.addView(detail("Open LAN sharing"))
        root.addView(body("Starting local location sharing."))
        root.addView(body("Discovery service: $MINILOC_NSD_SERVICE_TYPE"))
        root.addView(button("Pair with QR instead") { switchToPairingScan() })
    }

    private fun showOpenLanPermissionRequired() {
        stopCamera()
        pendingPairing = null
        pendingOpenLanStart = false
        resetRoot()
        root.addView(title("miniloc"))
        root.addView(detail("Open LAN sharing"))
        if (hasLocationPermission() && hasNotificationPermission()) {
            root.addView(body("Open LAN sharing is stopped."))
        } else {
            root.addView(body("Location and notification permissions are required before sharing can start."))
        }
        root.addView(button("Start Open LAN sharing") { ensureOpenLanPermissions() })
        root.addView(button("Pair with QR instead") { switchToPairingScan() })
    }

    private fun switchToPairingScan() {
        stopService(Intent(this, LocationForegroundService::class.java))
        PairingStore.disableOpenLan(this)
        pendingOpenLanStart = false
        requestCameraAndScan()
    }

    private fun requestCameraAndScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            showScanner()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
        }
    }

    private fun showScanner() {
        pendingPairing = null
        pendingOpenLanStart = false
        resetRoot()
        root.addView(title("Scan pairing QR"))
        root.addView(body("Point the camera at a miniloc pairing QR from the host device."))
        val preview = PreviewView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(420),
            ).apply {
                topMargin = dp(12)
                bottomMargin = dp(12)
            }
            scaleType = PreviewView.ScaleType.FILL_CENTER
            setOnTouchListener { view, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    view.performClick()
                    focusCameraAt(event.x, event.y)
                    true
                } else {
                    true
                }
            }
        }
        previewView = preview
        root.addView(preview)
        scannerStatusView = statusText("Camera starting")
        root.addView(scannerStatusView)
        root.addView(button("Cancel") { showOpenLanPermissionRequired() })
        startCamera(preview)
    }

    private fun showPairingConfirmation(pairing: HostPairing) {
        stopCamera()
        pendingPairing = pairing
        pendingOpenLanStart = false
        resetRoot()
        root.addView(title("Confirm pairing"))
        root.addView(body("Share this phone's location with:"))
        root.addView(detail(pairing.hostName))
        root.addView(body("Host ID: ${pairing.hostId}"))
        root.addView(body("Expires: ${expiryText(pairing)}"))
        root.addView(button("Start sharing") { ensureSharingPermissions(pairing) })
        root.addView(button("Cancel") { showOpenLanPermissionRequired() })
    }

    private fun showOpenLanConfirmation() {
        stopCamera()
        pendingPairing = null
        pendingOpenLanStart = true
        resetRoot()
        root.addView(title("Open LAN sharing"))
        root.addView(body("Share this phone's location with any device on the current local network."))
        root.addView(body("No pairing token is required while this mode is active."))
        root.addView(body("Discovery service: $MINILOC_NSD_SERVICE_TYPE"))
        root.addView(button("Start Open LAN sharing") { ensureOpenLanPermissions() })
        root.addView(button("Pair with QR instead") { switchToPairingScan() })
    }

    private fun ensureSharingPermissions(pairing: HostPairing) {
        pendingPairing = pairing
        pendingOpenLanStart = false
        requestSharingPermissions { savePairingAndStart(pairing) }
    }

    private fun ensureOpenLanPermissions() {
        pendingPairing = null
        pendingOpenLanStart = true
        requestSharingPermissions { startOpenLanSharing() }
    }

    private fun requestSharingPermissions(onGranted: () -> Unit) {
        val permissions = mutableListOf<String>()
        if (!hasLocationPermission()) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (!hasNotificationPermission() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.isEmpty()) {
            onGranted()
        } else {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_SHARING_PERMISSIONS)
        }
    }

    private fun savePairingAndStart(pairing: HostPairing) {
        PairingStore.save(this, pairing)
        pendingOpenLanStart = false
        startSharingService()
        showActive()
    }

    private fun startOpenLanSharing() {
        PairingStore.enableOpenLan(this)
        pendingOpenLanStart = false
        LocationCache.clear()
        startSharingService()
        showOpenLanActive()
    }

    private fun showActive() {
        stopCamera()
        val pairing = PairingStore.get(this) ?: return showUnpaired()
        resetRoot()
        root.addView(title("miniloc"))
        root.addView(detail("Sharing with ${pairing.hostName}"))
        root.addView(body("Host ID: ${pairing.hostId}"))
        root.addView(body("Service: ${if (LocationForegroundService.isRunning()) "active" else "stopped"}"))
        root.addView(body("Phone IP: ${phoneIpAddress() ?: "unknown"}"))
        root.addView(body("Port: ${pairing.minilocPort}"))

        val fix = LocationCache.get()
        if (fix == null) {
            root.addView(body("Latest fix: waiting"))
        } else {
            root.addView(body("Latest fix age: ${fix.ageMs()} ms"))
            root.addView(body("Latest accuracy: ${fix.horizontalAccuracyM?.let { "$it m" } ?: "unknown"}"))
        }

        if (LocationForegroundService.isRunning()) {
            root.addView(button("Stop sharing") {
                stopService(Intent(this, LocationForegroundService::class.java))
                showActive()
            })
        } else {
            root.addView(button("Start sharing") {
                ensureSharingPermissions(pairing)
            })
        }
        root.addView(button("Forget pairing") {
            stopService(Intent(this, LocationForegroundService::class.java))
            PairingStore.clear(this)
            LocationCache.clear()
            showOpenLanPermissionRequired()
        })
        root.addView(locationHistoryList())
    }

    private fun showUnpaired() {
        showOpenLanPermissionRequired()
    }

    private fun showOpenLanActive() {
        stopCamera()
        if (!PairingStore.isOpenLanEnabled(this)) return showUnpaired()
        resetRoot()
        val instanceId = PairingStore.instanceId(this)
        root.addView(title("miniloc"))
        root.addView(detail("Open LAN sharing"))
        root.addView(body("Instance ID: $instanceId"))
        root.addView(body("Service: ${if (LocationForegroundService.isRunning()) "active" else "stopped"}"))
        val ip = phoneIpAddress()
        root.addView(body("Phone URL: ${ip?.let { "http://$it:$MINILOC_PORT" } ?: "unknown"}"))
        root.addView(body("Discovery: ${PairingStore.serviceName(this)}.$MINILOC_NSD_SERVICE_TYPE"))

        val fix = LocationCache.get()
        if (fix == null) {
            root.addView(body("Latest fix: waiting"))
        } else {
            root.addView(body("Latest fix age: ${fix.ageMs()} ms"))
            root.addView(body("Latest accuracy: ${fix.horizontalAccuracyM?.let { "$it m" } ?: "unknown"}"))
        }

        if (LocationForegroundService.isRunning()) {
            root.addView(button("Stop sharing") {
                stopService(Intent(this, LocationForegroundService::class.java))
                PairingStore.disableOpenLan(this)
                LocationCache.clear()
                showOpenLanPermissionRequired()
            })
            root.addView(button("Pair with QR instead") { switchToPairingScan() })
        } else {
            root.addView(button("Start sharing") {
                ensureOpenLanPermissions()
            })
            root.addView(button("Pair with QR instead") { switchToPairingScan() })
        }
        root.addView(locationHistoryList())
    }

    private fun startSharingService() {
        val intent = Intent(this, LocationForegroundService::class.java)
            .setAction(LocationForegroundService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun startCamera(preview: PreviewView) {
        setScannerStatus("Camera starting")
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                try {
                    val cameraProvider = providerFuture.get()
                    val previewUseCase = Preview.Builder().build().also {
                        it.setSurfaceProvider(preview.surfaceProvider)
                    }
                    val analyzer = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(cameraExecutor) { imageProxy -> analyzeQr(imageProxy) }
                        }

                    cameraProvider.unbindAll()
                    camera = cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        previewUseCase,
                        analyzer,
                    )
                    setScannerStatus("Looking for QR. Tap preview to focus.")
                } catch (error: Exception) {
                    setScannerStatus("Camera failed: ${error.message ?: "unknown error"}")
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun focusCameraAt(x: Float, y: Float) {
        val preview = previewView ?: return
        val activeCamera = camera ?: run {
            setScannerStatus("Camera not ready")
            return
        }
        focusRequestSequence += 1
        val requestId = focusRequestSequence
        val point = preview.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
        )
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        setScannerStatus("Focusing")
        val focusFuture = activeCamera.cameraControl.startFocusAndMetering(action)
        focusFuture.addListener(
            {
                if (requestId != focusRequestSequence) return@addListener
                val result = runCatching { focusFuture.get() }.getOrElse { error ->
                    if (isFocusCancellation(error)) {
                        setScannerStatus("Looking for QR. Tap preview to focus.")
                    } else {
                        setScannerStatus("Focus failed. Try tapping the QR again.")
                    }
                    return@addListener
                }
                if (requestId != focusRequestSequence) return@addListener
                if (result.isFocusSuccessful) {
                    setScannerStatus("Focus locked. Looking for QR.")
                } else {
                    setScannerStatus("Focus adjusted. Looking for QR.")
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun isFocusCancellation(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val className = current::class.java.name
            val message = current.message.orEmpty()
            if (
                className.contains("OperationCanceledException") ||
                message.contains("OperationCanceledException") ||
                message.contains("cancelFocusAndMetering")
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analyzeQr(imageProxy: ImageProxy) {
        if (!analyzing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            analyzing.set(false)
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        BarcodeScanning.getClient()
            .process(image)
            .addOnCompleteListener { task ->
                handleBarcodeTask(task)
                imageProxy.close()
                analyzing.set(false)
            }
    }

    private fun handleBarcodeTask(task: Task<List<Barcode>>) {
        if (!task.isSuccessful) {
            setScannerStatus("Scanner error: ${task.exception?.message ?: "try again"}")
            return
        }
        val value = task.result?.firstOrNull { it.rawValue?.isNotBlank() == true }?.rawValue ?: return
        setScannerStatus("QR detected")
        val pairing = try {
            PairingStore.parsePayload(value)
        } catch (error: IllegalArgumentException) {
            val message = error.message ?: "Invalid pairing QR."
            setScannerStatus(message)
            mainHandler.post { toast(message) }
            return
        }
        mainHandler.post {
            setScannerStatus("Pairing QR found")
            pendingPairing = pairing
            showPairingConfirmation(pairing)
        }
    }

    private fun stopCamera() {
        focusRequestSequence += 1
        previewView = null
        scannerStatusView = null
        scannerStatusText = null
        camera = null
        runCatching { ProcessCameraProvider.getInstance(this).get().unbindAll() }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun resetRoot() {
        root.removeAllViews()
    }

    private fun setScannerStatus(text: String) {
        if (scannerStatusText == text) return
        scannerStatusText = text
        mainHandler.post {
            scannerStatusView?.text = text
        }
    }

    private fun title(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 30f
            setTextColor(0xFF102124.toInt())
            setPadding(0, 0, 0, dp(12))
        }
    }

    private fun detail(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 22f
            setTextColor(0xFF16383D.toInt())
            setPadding(0, dp(4), 0, dp(10))
        }
    }

    private fun body(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(0xFF263238.toInt())
            setPadding(0, dp(4), 0, dp(4))
        }
    }

    private fun statusText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(0xFF16383D.toInt())
            setPadding(0, 0, 0, dp(4))
        }
    }

    private fun locationHistoryList(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(18)
            }
        }

        container.addView(historyTitle("Location history"))
        val fixes = LocationCache.recent().asReversed()
        if (fixes.isEmpty()) {
            container.addView(historyRow("Waiting for location fixes"))
        } else {
            fixes.forEach { fix ->
                container.addView(historyRow(formatHistoryFix(fix)))
            }
        }
        return container
    }

    private fun historyTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(0xFF16383D.toInt())
            setPadding(0, 0, 0, dp(6))
        }
    }

    private fun historyRow(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(0xFF263238.toInt())
            setPadding(0, dp(2), 0, dp(2))
        }
    }

    private fun formatHistoryFix(fix: LocationFix): String {
        val timestamp = LOCATION_HISTORY_TIME_FORMAT.format(Instant.ofEpochMilli(fix.phoneWallTimeMs))
        val accuracy = fix.horizontalAccuracyM?.let { String.format(Locale.US, "%.1fm", it) } ?: "n/a"
        return String.format(
            Locale.US,
            "#%-3d %s age=%4dms delay=%4dms acc=%5s %.6f, %.6f",
            fix.sequence,
            timestamp,
            fix.ageMs(),
            fix.deliveryLatencyMs(),
            accuracy,
            fix.lat,
            fix.lon,
        )
    }

    private fun button(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            setAllCaps(false)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(12)
            }
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun expiryText(pairing: HostPairing): String {
        val expiresAt = pairing.expiresAtMillis ?: return "unknown"
        return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(expiresAt))
    }

    @Suppress("DEPRECATION")
    private fun phoneIpAddress(): String? {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val network = connectivityManager.activeNetwork ?: return wifiIpFallback()
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true &&
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) != true
        ) {
            return wifiIpFallback()
        }
        val linkProperties = connectivityManager.getLinkProperties(network) ?: return wifiIpFallback()
        return linkProperties.linkAddresses
            .mapNotNull { it.address.hostAddress }
            .firstOrNull { it.count { char -> char == '.' } == 3 && !it.startsWith("127.") }
            ?: wifiIpFallback()
    }

    @Suppress("DEPRECATION")
    private fun wifiIpFallback(): String? {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val ip = wifiManager.connectionInfo?.ipAddress ?: return null
        if (ip == 0) return null
        return Formatter.formatIpAddress(ip)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val REQUEST_CAMERA_PERMISSION = 11
        private const val REQUEST_SHARING_PERMISSIONS = 12
        private const val ACTIVE_REFRESH_INTERVAL_MS = 200L
        private val LOCATION_HISTORY_TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    }
}
