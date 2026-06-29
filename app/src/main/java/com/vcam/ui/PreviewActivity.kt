package com.vcam.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.vcam.R
import com.vcam.utils.MediaSlotManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen media preview with pinch-to-zoom and +/- buttons.
 * Launch with EXTRA_SLOT (Int) to preview that slot.
 */
class PreviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SLOT = "preview_slot"
    }

    private lateinit var imageView: ImageView
    private lateinit var tvSlotLabel: TextView
    private lateinit var tvZoom: TextView
    private lateinit var btnPlus: View
    private lateinit var btnMinus: View
    private lateinit var btnClose: View

    private val matrix = Matrix()
    private var scaleFactor = 1f
    private val minScale = 0.5f
    private val maxScale = 8f

    // For drag/pan
    private var lastX = 0f
    private var lastY = 0f
    private var isDragging = false
    private var bitmapWidth = 0f
    private var bitmapHeight = 0f

    private lateinit var scaleDetector: ScaleGestureDetector

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)

        imageView  = findViewById(R.id.iv_preview_image)
        tvSlotLabel= findViewById(R.id.tv_preview_slot_label)
        tvZoom     = findViewById(R.id.tv_preview_zoom)
        btnPlus    = findViewById(R.id.btn_preview_zoom_in)
        btnMinus   = findViewById(R.id.btn_preview_zoom_out)
        btnClose   = findViewById(R.id.btn_preview_close)

        val slot = intent.getIntExtra(EXTRA_SLOT, 1)
        val isVideo = MediaSlotManager.isSlotVideo(this, slot)
        tvSlotLabel.text = if (isVideo) "🎬 فيديو" else "📷 صورة $slot"

        btnClose.setOnClickListener { finish() }

        btnPlus.setOnClickListener  { applyZoom(0.25f) }
        btnMinus.setOnClickListener { applyZoom(-0.25f) }

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newScale = (scaleFactor * detector.scaleFactor).coerceIn(minScale, maxScale)
                val ds = newScale / scaleFactor
                matrix.postScale(ds, ds, detector.focusX, detector.focusY)
                scaleFactor = newScale
                imageView.imageMatrix = matrix
                updateZoomLabel()
                return true
            }
        })

        imageView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX; lastY = event.rawY
                    isDragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!scaleDetector.isInProgress) {
                        val dx = event.rawX - lastX
                        val dy = event.rawY - lastY
                        if (!isDragging && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) isDragging = true
                        if (isDragging) {
                            matrix.postTranslate(dx, dy)
                            imageView.imageMatrix = matrix
                            lastX = event.rawX; lastY = event.rawY
                        }
                    }
                }
            }
            true
        }

        // Load thumbnail in background
        CoroutineScope(Dispatchers.Main).launch {
            val bmp = withContext(Dispatchers.IO) { MediaSlotManager.getThumbnail(this@PreviewActivity, slot) }
            if (bmp != null) {
                bitmapWidth  = bmp.width.toFloat()
                bitmapHeight = bmp.height.toFloat()
                imageView.setImageBitmap(bmp)
                imageView.scaleType = ImageView.ScaleType.MATRIX
                centerImage(bmp)
            } else {
                tvSlotLabel.text = "لا توجد صورة في هذا الحقل"
            }
        }
    }

    private fun centerImage(bmp: Bitmap) {
        imageView.post {
            val vw = imageView.width.toFloat()
            val vh = imageView.height.toFloat()
            val scale = minOf(vw / bmp.width, vh / bmp.height)
            scaleFactor = scale
            matrix.reset()
            matrix.postScale(scale, scale)
            matrix.postTranslate((vw - bmp.width * scale) / 2f, (vh - bmp.height * scale) / 2f)
            imageView.imageMatrix = matrix
            updateZoomLabel()
        }
    }

    private fun applyZoom(delta: Float) {
        val newScale = (scaleFactor + delta).coerceIn(minScale, maxScale)
        val ds = newScale / scaleFactor
        val cx = imageView.width / 2f
        val cy = imageView.height / 2f
        matrix.postScale(ds, ds, cx, cy)
        scaleFactor = newScale
        imageView.imageMatrix = matrix
        updateZoomLabel()
    }

    private fun updateZoomLabel() {
        tvZoom.text = "${(scaleFactor * 100).toInt()}%"
    }
}
