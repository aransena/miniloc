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
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
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
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var cameraExecutor: ExecutorService
    private var pendingPairing: HostPairing? = null
    private var previewView: PreviewView? = null
    private val analyzing = AtomicBoolean(false)

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (PairingStore.get(this@MainActivity) != null && previewView == null) {
                showActive()
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

        if (PairingStore.get(this) == null) {
            showUnpaired()
        } else {
            showActive()
        }
    }

    override fun onResume() {
        super.onResume()
        if (PairingStore.get(this) != null && previewView == null) {
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
                    showUnpaired()
                }
            }
            REQUEST_SHARING_PERMISSIONS -> {
                val locationGranted = hasLocationPermission()
                val notificationGranted = hasNotificationPermission()
                if (locationGranted && notificationGranted) {
                    pendingPairing?.let { savePairingAndStart(it) }
                } else {
                    toast("Location and notification permissions are required to share location.")
                    pendingPairing?.let { showPairingConfirmation(it) } ?: showUnpaired()
                }
            }
        }
    }

    private fun showUnpaired() {
        stopCamera()
        pendingPairing = null
        resetRoot()
        root.addView(title("miniloc"))
        root.addView(body("Share this phone's location with a paired local host."))
        root.addView(button("Scan pairing QR") { requestCameraAndScan() })
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
        }
        previewView = preview
        root.addView(preview)
        root.addView(button("Cancel") { showUnpaired() })
        startCamera(preview)
    }

    private fun showPairingConfirmation(pairing: HostPairing) {
        stopCamera()
        pendingPairing = pairing
        resetRoot()
        root.addView(title("Confirm pairing"))
        root.addView(body("Share this phone's location with:"))
        root.addView(detail(pairing.hostName))
        root.addView(body("Host ID: ${pairing.hostId}"))
        root.addView(body("Expires: ${expiryText(pairing)}"))
        root.addView(button("Start sharing") { ensureSharingPermissions(pairing) })
        root.addView(button("Cancel") { showUnpaired() })
    }

    private fun ensureSharingPermissions(pairing: HostPairing) {
        pendingPairing = pairing
        val permissions = mutableListOf<String>()
        if (!hasLocationPermission()) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (!hasNotificationPermission() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.isEmpty()) {
            savePairingAndStart(pairing)
        } else {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_SHARING_PERMISSIONS)
        }
    }

    private fun savePairingAndStart(pairing: HostPairing) {
        PairingStore.save(this, pairing)
        startSharingService()
        showActive()
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
            showUnpaired()
        })
        root.addView(locationHistoryList())
    }

    private fun startSharingService() {
        val intent = Intent(this, LocationForegroundService::class.java)
            .setAction(LocationForegroundService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun startCamera(preview: PreviewView) {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
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
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    previewUseCase,
                    analyzer,
                )
            },
            ContextCompat.getMainExecutor(this),
        )
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
        if (!task.isSuccessful) return
        val value = task.result?.firstOrNull { it.rawValue?.isNotBlank() == true }?.rawValue ?: return
        val pairing = try {
            PairingStore.parsePayload(value)
        } catch (error: IllegalArgumentException) {
            mainHandler.post { toast(error.message ?: "Invalid pairing QR.") }
            return
        }
        mainHandler.post {
            pendingPairing = pairing
            showPairingConfirmation(pairing)
        }
    }

    private fun stopCamera() {
        previewView = null
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
