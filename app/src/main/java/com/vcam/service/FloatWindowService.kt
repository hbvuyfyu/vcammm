package com.vcam.service

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import com.vcam.R
import com.vcam.ui.MainActivity

/**
 * FloatWindowService — draggable floating overlay (like the analyzed app).
 *
 * Shows:
 *   • Status icon (camera on/off)
 *   • Rotation button (0→90→180→270)
 *   • Mirror toggle
 *   • Stop button
 *
 * Communicate rotation/mirror changes back to VCamService via broadcasts.
 */
class FloatWindowService : Service() {

    companion object {
        const val ACTION_START          = "com.vcam.float.START"
        const val ACTION_STOP_FLOAT     = "com.vcam.float.STOP"
        const val ACTION_UPDATE_STATUS  = "com.vcam.float.UPDATE_STATUS"

        const val EXTRA_TARGET_NAME     = "float_target_name"
        const val EXTRA_IS_VIDEO        = "float_is_video"

        /** Broadcast from float window to VCamService */
        const val ACTION_ROTATE         = "com.vcam.float.ROTATE"
        const val ACTION_MIRROR         = "com.vcam.float.MIRROR"
        const val ACTION_STOP_VCAM      = "com.vcam.float.STOP_VCAM"

        private const val CHANNEL_ID    = "vcam_float_channel"
        private const val NOTIF_ID      = 1002
    }

    private var windowManager: WindowManager? = null
    private var floatView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var currentRotation = 0
    private var isMirrored = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val targetName = intent.getStringExtra(EXTRA_TARGET_NAME) ?: "All Apps"
                val isVideo    = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)
                startForeground(NOTIF_ID, buildNotification(targetName, isVideo))
                showFloatWindow(targetName, isVideo)
            }
            ACTION_STOP_FLOAT -> {
                removeFloatWindow()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeFloatWindow()
        super.onDestroy()
    }

    // ── Float window ──────────────────────────────────────────────────

    private fun showFloatWindow(targetName: String, isVideo: Boolean) {
        if (floatView != null) return

        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.float_window, null)

        // Set target label
        view.findViewById<TextView>(R.id.tv_float_target)?.text =
            if (targetName.length > 18) targetName.take(16) + "…" else targetName
        view.findViewById<TextView>(R.id.tv_float_type)?.text =
            if (isVideo) "🎬 Video" else "🖼 Image"

        // Rotation button
        val btnRotate = view.findViewById<ImageButton>(R.id.btn_float_rotate)
        btnRotate?.setOnClickListener {
            currentRotation = (currentRotation + 90) % 360
            val btn = it as? ImageButton
            btn?.contentDescription = "Rotate ${currentRotation}°"
            sendBroadcast(Intent(ACTION_ROTATE).putExtra("rotation", currentRotation))
            Toast.makeText(this, "Rotation: ${currentRotation}°", Toast.LENGTH_SHORT).show()
        }

        // Mirror button
        val btnMirror = view.findViewById<ImageButton>(R.id.btn_float_mirror)
        btnMirror?.setOnClickListener {
            isMirrored = !isMirrored
            btnMirror.alpha = if (isMirrored) 1f else 0.5f
            sendBroadcast(Intent(ACTION_MIRROR).putExtra("mirror", isMirrored))
            Toast.makeText(this, if (isMirrored) "Mirror: ON" else "Mirror: OFF", Toast.LENGTH_SHORT).show()
        }

        // Stop button
        view.findViewById<ImageButton>(R.id.btn_float_stop)?.setOnClickListener {
            sendBroadcast(Intent(ACTION_STOP_VCAM))
            sendBroadcast(Intent(ACTION_STOP_FLOAT))
            removeFloatWindow()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        // Drag support
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 200
        }

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm
        layoutParams  = lp
        floatView     = view

        // Drag only on the header row, not on buttons
        view.findViewById<View>(R.id.tv_float_target)?.setOnTouchListener(
            DragTouchListener(view, wm, lp)
        )
        view.findViewById<View>(R.id.tv_float_type)?.setOnTouchListener(
            DragTouchListener(view, wm, lp)
        )
        wm.addView(view, lp)
    }

    private fun removeFloatWindow() {
        try {
            floatView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        floatView = null
    }

    // ── Drag listener ─────────────────────────────────────────────────

    private inner class DragTouchListener(
        private val view: View,
        private val wm: WindowManager,
        private val lp: WindowManager.LayoutParams
    ) : View.OnTouchListener {
        private var initX = 0; private var initY = 0
        private var touchX = 0f; private var touchY = 0f

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = lp.x; initY = lp.y
                    touchX = event.rawX; touchY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = initX + (event.rawX - touchX).toInt()
                    lp.y = initY + (event.rawY - touchY).toInt()
                    wm.updateViewLayout(view, lp)
                }
            }
            return false
        }
    }

    // ── Notification ──────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "VCam Float Window",
                NotificationManager.IMPORTANCE_MIN
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(targetName: String, isVideo: Boolean): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("VCam Float — Active")
            .setContentText("Injecting ${if (isVideo) "video" else "image"} → $targetName")
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }
}
