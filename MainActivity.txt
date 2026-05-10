package com.example.cctv.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.cctv.databinding.ActivityMainBinding
import com.example.cctv.service.CameraForegroundService
import com.example.cctv.util.ScreenPowerManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var cctvService: CameraForegroundService? = null
    private var powerSaverActive = false

    // Clock updater
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockFormat  = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val clockRunnable = object : Runnable {
        override fun run() {
            binding.tvClock.text = clockFormat.format(Date())
            clockHandler.postDelayed(this, 1000)
        }
    }

    // Dot pulse animation
    private val pulseAnim = AlphaAnimation(1f, 0.2f).apply {
        duration        = 800
        repeatMode      = Animation.REVERSE
        repeatCount     = Animation.INFINITE
    }

    // ── Permissions ─────────────────────────────────────────────────────────
    private val requiredPermissions = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) startCctvService()
        else Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
    }

    // ── Service connection ───────────────────────────────────────────────────
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = (binder as CameraForegroundService.LocalBinder).getService()
            cctvService = service

            // Wire CameraX preview surface to our PreviewView
            service.attachPreviewView(binding.previewView)

            observeServiceState(service)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            cctvService = null
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUi()
        checkPermissionsAndStart()
        promptBatteryOptimization()
    }

    override fun onStart() {
        super.onStart()
        clockHandler.post(clockRunnable)
        binding.indicatorDot.startAnimation(pulseAnim)
        val intent = Intent(this, CameraForegroundService::class.java)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        clockHandler.removeCallbacks(clockRunnable)
        binding.indicatorDot.clearAnimation()
        unbindService(serviceConnection)
        super.onStop()
    }

    // Touch anywhere in power-saver mode to wake screen
    override fun onUserInteraction() {
        super.onUserInteraction()
        if (powerSaverActive) disablePowerSaver()
    }

    // ── UI setup ─────────────────────────────────────────────────────────────
    private fun setupUi() {

        // Copy URL to clipboard
        binding.btnCopyUrl.setOnClickListener {
            val url = binding.tvStreamUrl.text.toString()
            if (url.startsWith("http")) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Stream URL", url))
                Toast.makeText(this, "URL copied!", Toast.LENGTH_SHORT).show()
            }
        }

        // Power Saver
        binding.btnPowerSaver.setOnClickListener {
            if (powerSaverActive) disablePowerSaver() else enablePowerSaver()
        }

        // Stop service
        binding.btnStop.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Stop CCTV?")
                .setMessage("The camera and stream will be shut down.")
                .setPositiveButton("Stop") { _, _ ->
                    startService(
                        Intent(this, CameraForegroundService::class.java)
                            .apply { action = CameraForegroundService.ACTION_STOP }
                    )
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Settings
        binding.btnSettings.setOnClickListener {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .addToBackStack(null)
                .commit()
        }
    }

    // ── Observe service state ────────────────────────────────────────────────
    private fun observeServiceState(service: CameraForegroundService) {
        lifecycleScope.launch {
            service.streamUrl.collect { url ->
                binding.tvStreamUrl.text = url
            }
        }
        lifecycleScope.launch {
            service.motionDetected.collect { detected ->
                if (detected) {
                    binding.tvMotionStatus.text = "⚠ MOTION DETECTED"
                    binding.tvMotionStatus.setTextColor(
                        ContextCompat.getColor(this@MainActivity, com.example.cctv.R.color.red_alert))
                    flashMotionOverlay()
                } else {
                    binding.tvMotionStatus.text = "● MONITORING"
                    binding.tvMotionStatus.setTextColor(
                        ContextCompat.getColor(this@MainActivity, com.example.cctv.R.color.green_accent))
                }
            }
        }
        lifecycleScope.launch {
            service.isRecording.collect { recording ->
                binding.recBadge.visibility = if (recording) View.VISIBLE else View.INVISIBLE
            }
        }
    }

    // ── Motion flash ─────────────────────────────────────────────────────────
    private fun flashMotionOverlay() {
        binding.motionFlash.animate()
            .alpha(0.25f).setDuration(120)
            .withEndAction {
                binding.motionFlash.animate().alpha(0f).setDuration(400).start()
            }.start()
    }

    // ── Power saver ──────────────────────────────────────────────────────────
    private fun enablePowerSaver() {
        powerSaverActive = true
        ScreenPowerManager.enablePowerSaverMode(this)
        // Hide UI so screen appears fully black
        binding.overlayCard.visibility   = View.INVISIBLE
        binding.statusBar.visibility     = View.INVISIBLE
        binding.tvMotionStatus.visibility = View.INVISIBLE
        binding.recBadge.visibility       = View.INVISIBLE
    }

    private fun disablePowerSaver() {
        powerSaverActive = false
        ScreenPowerManager.disablePowerSaverMode(this)
        binding.overlayCard.visibility    = View.VISIBLE
        binding.statusBar.visibility      = View.VISIBLE
        binding.tvMotionStatus.visibility = View.VISIBLE
    }

    // ── Permissions & service ────────────────────────────────────────────────
    private fun checkPermissionsAndStart() {
        val allGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) startCctvService() else permissionLauncher.launch(requiredPermissions)
    }

    private fun startCctvService() {
        ContextCompat.startForegroundService(
            this, Intent(this, CameraForegroundService::class.java))
    }

    private fun promptBatteryOptimization() {
        if (!ScreenPowerManager.isBatteryOptimizationIgnored(this)) {
            AlertDialog.Builder(this)
                .setTitle("Battery Optimization")
                .setMessage(
                    "Disable battery optimization for 24/7 reliability. " +
                    "Without this, Android may kill the camera service."
                )
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    ))
                }
                .setNegativeButton("Later", null)
                .show()
        }
    }
}
