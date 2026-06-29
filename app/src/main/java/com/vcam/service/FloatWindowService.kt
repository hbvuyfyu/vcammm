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
import com.vcam.ui.PreviewActivity
import com.vcam.utils.MediaSlotManager

/**
 * FloatWindowService — draggable floating overlay.
 *
 * Shows:
 *   • Slot switcher buttons [1][2][3][4][5]
 *   • Preview button (👁) → opens PreviewActivity for current slot
 *   • Rotation, Mirror, Stop buttons
 */
class FloatWindowService : Service() {

    companion object {
        const val ACTION_START          = "com.vcam.float.START"
        const val ACTION_STOP_FLOAT     = "com.vcam.float.STOP"
        const val ACTION_UPDATE_STATUS  = "com.vcam.float.UPDATE_STATUS"

        const val EXTRA_TARGET_NAME     = "float_target_name"
        const val EXTRA_IS_VIDEO        = "float_is_video"

        /** Broadcasts to VCamService */
        const val ACTION_ROTATE         = "com.vcam.float.ROTATE"
        const val ACTION_MIRROR         = "com.vcam.float.MIRROR"
        const val ACTION_STOP_VCAM      = "com.vcam.float.STOP_VCAM"
        const val ACTION_SWITCH_SLOT    = "com.vcam.float.SWITCH_SLOT"
        const val EXTRA_SLOT            = "slot_number"

        private const val CHANNEL_ID    = "vcam_float_channel"
        private const val NOTIF_ID      = 1002
    }

    private var windowManager: WindowManager? = null
    private var floatView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var currentRotation = 0
    private var isMirrored = false
    private var activeSlot = 1

    // Slot button views
    private val slotBtnIds = listOf(
        R.id.btn_slot_1, R.id.btn_slot_2, R.id.btn_slot_3,
        R.id.btn_slot_4, R.id.btn_slot_5
    )

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

    override fun onDestroy() { removeFloatWindow(); super.onDestroy() }

    // ── Float window ──────────────────────────────────────────────────

    private fun showFloatWindow(targetName: String, isVideo: Boolean) {
        if (floatView != null) return

        val view = LayoutInflater.from(this).inflate(R.layout.float_window, null)

        // Status labels
        view.findViewById<TextView>(R.id.tv_float_target)?.text =
            if (targetName.length > 16) targetName.take(14) + "…" else targetName
        updateTypeLabel(view, activeSlot)

        // ── Slot buttons ──
        slotBtnIds.forEachIndexed { idx, btnId ->
            val slot = idx + 1
            view.findViewById<TextView>(btnId)?.setOnClickListener {
                switchToSlot(view, slot)
            }
        }
        // Mark slot 1 as active initially
        updateSlotButtonVisuals(view, activeSlot)

        // ── Preview button ──
        view.findViewById<ImageButton>(R.id.btn_float_preview)?.setOnClickListener {
            openPreview(activeSlot)
        }

        // ── Rotate ──
        view.findViewById<ImageButton>(R.id.btn_float_rotate)?.setOnClickListener {
            currentRotation = (currentRotation + 90) % 360
            sendBroadcast(Intent(ACTION_ROTATE).putExtra("rotation", currentRotation))
            Toast.makeText(this, "دوران: ${currentRotation}°", Toast.LENGTH_SHORT).show()
        }

        // ── Mirror ──
        val btnMirror = view.findViewById<ImageButton>(R.id.btn_float_mirror)
        btnMirror?.setOnClickListener {
            isMirrored = !isMirrored
            btnMirror.alpha = if (isMirrored) 1f else 0.5f
            sendBroadcast(Intent(ACTION_MIRROR).putExtra("mirror", isMirrored))
            Toast.makeText(this, if (isMirrored) "عكس: ON" else "عكس: OFF", Toast.LENGTH_SHORT).show()
        }

        // ── Stop ──
        view.findViewById<ImageButton>(R.id.btn_float_stop)?.setOnClickListener {
            sendBroadcast(Intent(ACTION_STOP_VCAM))
            removeFloatWindow()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        // Window params
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 50; y = 200 }

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm; layoutParams = lp; floatView = view

        // Drag on header
        val dragHandle = view.findViewById<View>(R.id.float_drag_handle)
        dragHandle?.setOnTouchListener(DragTouchListener(view, wm, lp))
        wm.addView(view, lp)
    }

    // ── Slot switching ────────────────────────────────────────────────

    private fun switchToSlot(view: View, slot: Int) {
        if (!MediaSlotManager.isSlotSet(this, slot)) {
            Toast.makeText(this, getString(R.string.slot_not_set, slot), Toast.LENGTH_SHORT).show()
            return
        }
        activeSlot = slot
        updateSlotButtonVisuals(view, slot)
        updateTypeLabel(view, slot)
        sendBroadcast(Intent(ACTION_SWITCH_SLOT).putExtra(EXTRA_SLOT, slot))
        Toast.makeText(this, getString(R.string.slot_switched, slot), Toast.LENGTH_SHORT).show()
    }

    private fun updateSlotButtonVisuals(view: View, active: Int) {
        slotBtnIds.forEachIndexed { idx, btnId ->
            val slot = idx + 1
            val tv = view.findViewById<TextView>(btnId) ?: return@forEachIndexed
            val isActive = slot == active
            tv.textColors?.let { }  // just trigger refresh
            tv.setTextColor(if (isActive) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt())
            tv.setBackgroundResource(
                when {
                    isActive && slot == 5 -> R.drawable.bg_slot_btn_video_active
                    isActive              -> R.drawable.bg_slot_btn_active
                    slot == 5            -> R.drawable.bg_slot_btn_video
                    else                 -> R.drawable.bg_slot_btn_inactive
                }
            )
        }
    }

    private fun updateTypeLabel(view: View, slot: Int) {
        view.findViewById<TextView>(R.id.tv_float_type)?.text =
            if (slot == 5) "🎬 فيديو" else "📷 صورة $slot"
    }

    // ── Preview ───────────────────────────────────────────────────────

    private fun openPreview(slot: Int) {
        val intent = Intent(this, PreviewActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(PreviewActivity.EXTRA_SLOT, slot)
        }
        startActivity(intent)
    }

    private fun removeFloatWindow() {
        try { floatView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
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
            NotificationChannel(CHANNEL_ID, "VCam Float Window", NotificationManager.IMPORTANCE_MIN).let {
                getSystemService(NotificationManager::class.java)?.createNotificationChannel(it)
            }
        }
    }

    private fun buildNotification(targetName: String, isVideo: Boolean): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("VCam Float — Active")
            .setContentText("Injecting ${if (isVideo) "video" else "image"} → $targetName")
            .setContentIntent(pi).setPriority(NotificationCompat.PRIORITY_MIN).setOngoing(true).build()
    }
}
