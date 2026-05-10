package com.example.cctv.service

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.camera.view.PreviewView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.cctv.CctvApplication.Companion.CHANNEL_SERVICE
import com.example.cctv.CctvApplication.Companion.PREFS_NAME
import com.example.cctv.R
import com.example.cctv.alert.AlertManager
import com.example.cctv.camera.CameraProvider
import com.example.cctv.camera.MotionAnalyzer
import com.example.cctv.storage.VideoStorageManager
import com.example.cctv.streaming.MjpegServer
import com.example.cctv.ui.MainActivity
import com.example.cctv.util.NetworkUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class CameraForegroundService : LifecycleService() {

    inner class LocalBinder : Binder() {
        fun getService() = this@CameraForegroundService
    }

    private val binder = LocalBinder()

    // ── Public state (observed by MainActivity) ──────────────────────────────
    private val _streamUrl      = MutableStateFlow("Starting…")
    private val _motionDetected = MutableStateFlow(false)
    private val _isRecording    = MutableStateFlow(false)

    val streamUrl:      StateFlow<String>  = _streamUrl
    val motionDetected: StateFlow<Boolean> = _motionDetected
    val isRecording:    StateFlow<Boolean> = _isRecording

    // ── Components ───────────────────────────────────────────────────────────
    private lateinit var mjpegServer:       MjpegServer
    private lateinit var motionAnalyzer:    MotionAnalyzer
    private lateinit var cameraProvider:    CameraProvider
    private lateinit var videoStorageManager: VideoStorageManager
    private lateinit var alertManager:      AlertManager

    // ── WakeLock ─────────────────────────────────────────────────────────────
    private var wakeLock: PowerManager.WakeLock? = null

    // ── Settings ─────────────────────────────────────────────────────────────
    private var sensitivityThreshold = 0.02f
    private var recordingDurationMs  = 15_000L
    private var webhookUrl           = ""

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
        initComponents()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        loadPreferences()
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startForegroundWithNotification()
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        if (::mjpegServer.isInitialized)    mjpegServer.stop()
        if (::cameraProvider.isInitialized) cameraProvider.shutdown()
        releaseWakeLock()
        super.onDestroy()
    }

    // ── Public API for MainActivity ───────────────────────────────────────────
    /** Wire CameraX Preview to the UI's PreviewView when Activity binds. */
    fun attachPreviewView(previewView: PreviewView) {
        if (::cameraProvider.isInitialized) {
            cameraProvider.setPreviewView(previewView)
        }
    }

    // ── Initialisation ────────────────────────────────────────────────────────
    private fun initComponents() {
        videoStorageManager = VideoStorageManager(this)
        alertManager        = AlertManager(this)
        mjpegServer         = MjpegServer(port = 8080)

        motionAnalyzer = MotionAnalyzer(
            sensitivityThreshold = sensitivityThreshold,
            onFrameReady  = { jpegBytes -> mjpegServer.pushFrame(jpegBytes) },
            onMotionEvent = { handleMotionEvent() }
        )

        cameraProvider = CameraProvider(
            context   = this,
            lifecycle = this,
            analyzer  = motionAnalyzer,
            storage   = videoStorageManager
        )

        lifecycleScope.launch {
            val ip  = NetworkUtils.getLocalIpAddress(this@CameraForegroundService)
            _streamUrl.value = "http://$ip:8080"
            mjpegServer.start()
            cameraProvider.bindCamera()
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        sensitivityThreshold = prefs.getFloat("sensitivity", 0.02f)
        recordingDurationMs  = prefs.getLong("recording_duration_ms", 15_000L)
        webhookUrl           = prefs.getString("webhook_url", "") ?: ""
        if (::motionAnalyzer.isInitialized) motionAnalyzer.updateThreshold(sensitivityThreshold)
    }

    // ── Motion ────────────────────────────────────────────────────────────────
    private var recordingInProgress = false

    private fun handleMotionEvent() {
        _motionDetected.value = true
        lifecycleScope.launch {
            alertManager.dispatch(webhookUrl)
            if (!recordingInProgress) {
                recordingInProgress     = true
                _isRecording.value      = true
                cameraProvider.startRecording(recordingDurationMs) {
                    recordingInProgress = false
                    _isRecording.value  = false
                }
            }
        }
        // Auto-reset motion flag after 6 s
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            { _motionDetected.value = false }, 6_000L
        )
    }

    // ── Foreground notification ───────────────────────────────────────────────
    private fun startForegroundWithNotification() {
        val stopPI = PendingIntent.getService(
            this, 0,
            Intent(this, CameraForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        val openPI = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setContentTitle("CCTV Active")
            .setContentText("Live at ${_streamUrl.value}")
            .setSmallIcon(R.drawable.ic_camera)
            .setOngoing(true)
            .setContentIntent(openPI)
            .addAction(R.drawable.ic_stop, "Stop", stopPI)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ── WakeLock ──────────────────────────────────────────────────────────────
    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CCTV::CameraWakeLock")
            .also { it.acquire(24 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        const val ACTION_STOP     = "com.example.cctv.ACTION_STOP"
        const val NOTIFICATION_ID = 1001
    }
}
