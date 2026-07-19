package com.vcam.service

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import com.vcam.R
import com.vcam.ui.MainActivity
import com.vcam.ui.PreviewActivity
import com.vcam.utils.MediaSlotManager

/**
 * FloatWindowService — draggable floating overlay with collapsed/expanded states.
 *
 * Collapsed: shows just the app icon. Tap to expand the full control panel.
 * Expanded: shows slot switcher, preview/rotate/mirror/stop buttons.
 *           Tap the icon or close button to collapse back.
 * Both states are draggable anywhere on screen.
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

        private const val CLICK_THRESHOLD_MS = 250L
        private const val CLICK_SLOP_PX  = 12f
    }

    private var windowManager: WindowManager? = null
    private var floatView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var currentRotation = 0
    private var isMirrored = false
    private var activeSlot = 1
    private var isExpanded = false

    private var collapsedView: View? = null
    private var expandedView: View? = null

    private val slotBtnIds = listOf(
        R.id.btn_slot_1, R.id.btn_slot_2, R.id.btn_slot_3,
        R.id.btn_slot_4, R.id.btn_slot_5, R.id.btn_slot_6,
        R.id.btn_slot_7, R.id.btn_slot_8
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

        collapsedView = view.findViewById(R.id.float_collapsed)
        expandedView  = view.findViewById(R.id.float_expanded)

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

        // ── Collapse button (X) in expanded header ──
        view.findViewById<ImageButton>(R.id.btn_float_collapse)?.setOnClickListener {
            collapse()
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

        // Attach the unified drag+tap listener to both the collapsed icon
        // and the expanded header so the user can drag from anywhere.
        val dragListener = DragAndTapListener(view, wm, lp)

        collapsedView?.setOnTouchListener(dragListener)
        val dragHandle = view.findViewById<View>(R.id.float_drag_handle)
        dragHandle?.setOnTouchListener(dragListener)

        wm.addView(view, lp)
    }

    // ── Collapse / Expand ────────────────────────────────────────────

    private fun collapse() {
        isExpanded = false
        collapsedView?.visibility = View.VISIBLE
        expandedView?.visibility  = View.GONE
    }

    private fun expand() {
        isExpanded = true
        collapsedView?.visibility = View.GONE
        expandedView?.visibility  = View.VISIBLE
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
            tv.setTextColor(if (isActive) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt())
            tv.setBackgroundResource(
                when {
                    isActive && slot >= 5 -> R.drawable.bg_slot_btn_video_active
                    isActive              -> R.drawable.bg_slot_btn_active
                    slot >= 5             -> R.drawable.bg_slot_btn_video
                    else                  -> R.drawable.bg_slot_btn_inactive
                }
            )
        }
    }

    private fun updateTypeLabel(view: View, slot: Int) {
        view.findViewById<TextView>(R.id.tv_float_type)?.text =
            if (slot >= 5) "🎬 فيديو ${slot - 4}" else "📷 صورة $slot"
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

    // ── Unified drag + tap listener ───────────────────────────────────

    /**
     * Handles both dragging the overlay and detecting taps (to toggle
     * collapsed/expanded state). Uses a time + slop threshold to distinguish
     * a tap from a drag gesture.
     */
    private inner class DragAndTapListener(
        private val view: View,
        private val wm: WindowManager,
        private val lp: WindowManager.LayoutParams
    ) : View.OnTouchListener {
        private var initX = 0
        private var initY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var downTime = 0L
        private var moved = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = lp.x; initY = lp.y
                    touchX = event.rawX; touchY = event.rawY
                    downTime = SystemClock.uptimeMillis()
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (Math.abs(dx) > CLICK_SLOP_PX || Math.abs(dy) > CLICK_SLOP_PX) {
                        moved = true
                    }
                    if (moved) {
                        lp.x = initX + dx.toInt()
                        lp.y = initY + dy.toInt()
                        wm.updateViewLayout(view, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = SystemClock.uptimeMillis() - downTime
                    if (!moved && elapsed < CLICK_THRESHOLD_MS) {
                        // Treat as a tap → toggle expanded/collapsed
                        if (isExpanded) collapse() else expand()
                    }
                }
            }
            return true
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
