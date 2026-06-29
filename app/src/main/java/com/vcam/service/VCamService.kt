package com.vcam.service

import android.app.*
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.vcam.R
import com.vcam.ui.MainActivity
import com.vcam.utils.CameraInjector
import com.vcam.utils.MediaSlotManager
import com.vcam.utils.RootManager
import com.vcam.utils.VcplaxEngine
import kotlinx.coroutines.*

class VCamService : Service() {

    companion object {
        const val ACTION_START          = "com.vcam.ACTION_START"
        const val ACTION_STOP           = "com.vcam.ACTION_STOP"
        const val EXTRA_MEDIA_URI       = "extra_media_uri"   // kept for compatibility
        const val EXTRA_MEDIA_PATH      = "extra_media_path"  // preferred: absolute path
        const val EXTRA_TARGET_PACKAGE  = "extra_target_package"
        const val EXTRA_TARGET_NAME     = "extra_target_name"
        const val EXTRA_IS_VIDEO        = "extra_is_video"

        private const val CHANNEL_ID      = "vcam_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var injector: CameraInjector? = null

    /** Receives rotation / mirror / slot-switch commands from FloatWindowService */
    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                FloatWindowService.ACTION_ROTATE -> {
                    val r = intent.getIntExtra("rotation", 0)
                    injector?.rotation = r
                    VcplaxEngine.setRotation(r)
                }
                FloatWindowService.ACTION_MIRROR -> {
                    val m = intent.getBooleanExtra("mirror", false)
                    injector?.mirror = m
                    VcplaxEngine.setMirror(m)
                }
                FloatWindowService.ACTION_SWITCH_SLOT -> {
                    val slot = intent.getIntExtra(FloatWindowService.EXTRA_SLOT, 1)
                    switchToSlot(slot)
                }
                FloatWindowService.ACTION_STOP_VCAM -> {
                    stopInjection()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val filter = IntentFilter().apply {
            addAction(FloatWindowService.ACTION_ROTATE)
            addAction(FloatWindowService.ACTION_MIRROR)
            addAction(FloatWindowService.ACTION_SWITCH_SLOT)
            addAction(FloatWindowService.ACTION_STOP_VCAM)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(controlReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(controlReceiver, filter)
        }
    }

    override fun onDestroy() {
        unregisterReceiver(controlReceiver)
        stopFloatWindow()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // Accept either a raw path (preferred) or a URI (legacy)
                val mediaPath: String? = intent.getStringExtra(EXTRA_MEDIA_PATH)
                    ?: intent.getStringExtra(EXTRA_MEDIA_URI)?.let { uriStr ->
                        try {
                            val uri = Uri.parse(uriStr)
                            val isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)
                            val tmp = copyUriToFile(uri, if (isVideo) "vcam_input.mp4" else "vcam_input.jpg")
                            tmp?.absolutePath
                        } catch (_: Exception) { null }
                    }

                if (mediaPath == null) return START_NOT_STICKY

                val targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
                val targetName    = intent.getStringExtra(EXTRA_TARGET_NAME) ?: "All Apps"
                val isVideo       = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)

                startForeground(NOTIFICATION_ID, buildNotification(targetName, isVideo, "Setting up…"))
                serviceScope.launch { startInjection(mediaPath, targetPackage, targetName, isVideo) }

                if (Settings.canDrawOverlays(this)) startFloatWindow(targetName, isVideo)
            }
            ACTION_STOP -> {
                stopInjection()
                stopFloatWindow()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    // ── Injection ─────────────────────────────────────────────────────

    private suspend fun startInjection(
        mediaPath: String, targetPackage: String?,
        targetName: String, isVideo: Boolean
    ) {
        try {
            injector = CameraInjector(
                context       = this,
                mediaPath     = mediaPath,
                isVideo       = isVideo,
                targetPackage = targetPackage
            )
            injector?.start()
            updateNotification(
                "VCam Active ✓",
                "System-wide injection active — open any camera app"
            )
        } catch (e: Exception) {
            updateNotification("VCam Error", e.message ?: "Unknown error")
        }
    }

    /** Hot-swap to a different media slot while injection is running. */
    private fun switchToSlot(slot: Int) {
        val path    = MediaSlotManager.getSlotPath(this, slot) ?: return
        val isVideo = MediaSlotManager.isSlotVideo(this, slot)
        serviceScope.launch {
            try {
                // Try vcplax hot-swap first (no interruption)
                val proxy = VcplaxEngine.getProxy()
                if (proxy != null && VcplaxEngine.isRunning) {
                    proxy.switchSource(path, if (isVideo) 2 else 1)
                } else {
                    // Restart injection with new media
                    injector?.stop()
                    injector = CameraInjector(
                        context       = this@VCamService,
                        mediaPath     = path,
                        isVideo       = isVideo,
                        targetPackage = null
                    )
                    injector?.start()
                }
                updateNotification("VCam — Slot $slot Active",
                    if (isVideo) "🎬 فيديو" else "📷 صورة $slot")
            } catch (_: Exception) {}
        }
    }

    private fun stopInjection() {
        injector?.stop()
        injector = null
    }

    // ── Float window ──────────────────────────────────────────────────

    private fun startFloatWindow(targetName: String, isVideo: Boolean) {
        val i = Intent(this, FloatWindowService::class.java).apply {
            action = FloatWindowService.ACTION_START
            putExtra(FloatWindowService.EXTRA_TARGET_NAME, targetName)
            putExtra(FloatWindowService.EXTRA_IS_VIDEO, isVideo)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
        else startService(i)
    }

    private fun stopFloatWindow() {
        startService(Intent(this, FloatWindowService::class.java).apply {
            action = FloatWindowService.ACTION_STOP_FLOAT
        })
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun copyUriToFile(uri: Uri, name: String): java.io.File? {
        return try {
            val dest = java.io.File(filesDir, name)
            contentResolver.openInputStream(uri)?.use { ins ->
                java.io.FileOutputStream(dest).use { out -> ins.copyTo(out) }
            }
            dest
        } catch (_: Exception) { null }
    }

    // ── Notification ──────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "VCam Injection", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "VCam camera injection service" }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(targetName: String, isVideo: Boolean, status: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopPi = PendingIntent.getService(this, 1,
            Intent(this, VCamService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vcam_notif)
            .setContentTitle("VCam — ${if (isVideo) "Video" else "Image"} → $targetName")
            .setContentText(status)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_media_pause, "Stop VCam", stopPi)
            .setOngoing(true).build()
    }

    private fun updateNotification(title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildNotification(title, false, text))
    }
}
