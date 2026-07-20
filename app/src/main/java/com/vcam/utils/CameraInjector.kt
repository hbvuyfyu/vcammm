package com.vcam.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * CameraInjector — system-wide virtual camera injection.
 *
 * Primary strategy: VcplaxEngine (ShadowHook-based inline hooking via vcplax binary)
 *   - Works on Android 10–14 with Camera2 API
 *   - Injects into cameraserver process at HAL level
 *   - No v4l2loopback kernel module required
 *
 * Fallback strategy: legacy LD_PRELOAD + v4l2loopback
 *   - For older devices / kernels that support it
 *
 * Zoom/scale/pan/rotation/mirror changes apply in real-time (within ~25ms).
 */
class CameraInjector(
    private val context: Context,
    private val mediaPath: String,
    private val isVideo: Boolean,
    private val targetPackage: String?,
    @Volatile var rotation: Int = 0,
    @Volatile var mirror: Boolean = false,
    @Volatile var zoomFactor: Float = 1f,
    @Volatile var frameFillScale: Float = 1f,
    @Volatile var panX: Int = 0,
    @Volatile var panY: Int = 0
) {
    companion object {
        private const val TAG      = "CameraInjector"
        private const val VCAM_DIR = "/data/local/tmp/vcam"
        private const val INJECT_LIB = "/data/local/tmp/libvcam_inject.so"
        private const val FRAME_FILE = "$VCAM_DIR/frame.yuyv"
        private const val META_FILE  = "$VCAM_DIR/frame_info"

        const val TARGET_W = 1280
        const val TARGET_H = 720

        // Re-render poll interval for image mode (fast response to transform changes)
        private const val RENDER_POLL_MS = 25L

        init {
            try { System.loadLibrary("vcam_native") }
            catch (e: UnsatisfiedLinkError) { Log.w(TAG, "vcam_native: ${e.message}") }
        }

        @JvmStatic external fun nativeStartFrameLoop(width: Int, height: Int, videoDevice: String): Boolean
        @JvmStatic external fun nativeUpdateYUYVFrame(yuyvData: ByteArray, width: Int, height: Int)
        @JvmStatic external fun nativeStopInjection()
        @JvmStatic external fun nativeCheckDevice(videoDevice: String): Boolean
        @JvmStatic external fun nativeInjectImage(imagePath: String, videoDevice: String): Int
        @JvmStatic external fun nativeInjectVideo(videoPath: String, videoDevice: String): Int
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    @Volatile private var running = false
    private var injectionJob: Job? = null
    @Volatile private var usingVcplax = false

    // Track last rendered transform state for real-time change detection
    @Volatile private var lastRenderedZoom     = Float.MIN_VALUE
    @Volatile private var lastRenderedScale    = Float.MIN_VALUE
    @Volatile private var lastRenderedPanX     = Int.MIN_VALUE
    @Volatile private var lastRenderedPanY     = Int.MIN_VALUE
    @Volatile private var lastRenderedRotation = Int.MIN_VALUE
    @Volatile private var lastRenderedMirror   = false

    // ── Public API ──────────────────────────────────────────────────────────

    fun start() {
        running = true
        injectionJob = scope.launch { performInjection() }
    }

    fun stop() {
        running = false
        injectionJob?.cancel()

        if (usingVcplax) {
            // Stop via VcplaxEngine
            VcplaxEngine.stopInjection()
            usingVcplax = false
        } else {
            // Legacy cleanup
            cleanupAllWrapProps()
            try { nativeStopInjection() } catch (_: Exception) {}
            RootManager.runCommands(
                "pkill -f ffmpeg 2>/dev/null || true",
                "pkill -f v4l2  2>/dev/null || true"
            )
            RootManager.runCommand("setprop ctl.restart cameraserver")
        }
        Log.d(TAG, "VCam stopped")
    }

    /**
     * Stop only the monitor loop but keep vcplax running.
     * Used when switching slots so the new CameraInjector can reuse the
     * already-running vcplax process instead of restarting it from scratch.
     */
    fun stopMonitorOnly() {
        running = false
        injectionJob?.cancel()
        usingVcplax = false
        Log.d(TAG, "Monitor stopped (vcplax kept alive)")
    }

    // ── Core injection pipeline ─────────────────────────────────────────────

    private suspend fun performInjection() {
        Log.d(TAG, "performInjection: isVideo=$isVideo target=$targetPackage")

        // ── PRIMARY: VcplaxEngine (ShadowHook-based, works on Android 10-14) ──
        try {
            val engineReady = VcplaxEngine.setup(context)
            if (engineReady) {
                val started = VcplaxEngine.startInjection(mediaPath, loop = isVideo)
                if (started) {
                    usingVcplax = true
                    Log.d(TAG, "VcplaxEngine injection active ✓")

                    // CRITICAL: do NOT call setRotation/setMirror here and do NOT
                    // start a watchdog that can restart injection.
                    //
                    // The watchdog approach (check isRunning every 500ms → restart on failure)
                    // was the root cause of the "plays ~1 second then loops" bug:
                    // vcplax can briefly report a non-5 status during normal video playback,
                    // which caused the watchdog to call startInjection() again, resetting
                    // vcplax's internal playback position to 0 every ~1.5 seconds.
                    //
                    // The correct approach (confirmed by the reference implementation):
                    // trust vcplax to handle its own video loop; only monitor transform
                    // changes (rotation, mirror, zoom, pan) from the Kotlin side.
                    monitorVcplaxTransforms()
                    return
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "VcplaxEngine failed: ${e.message}")
        }

        // ── FALLBACK: Legacy HAL1 + v4l2loopback ──────────────────────────────
        Log.w(TAG, "VcplaxEngine unavailable — falling back to legacy injection")
        legacyInject()
    }

    /**
     * For the VcplaxEngine path: monitor transform changes and apply in real-time.
     *
     * For images: ALL transforms (rotation, mirror, zoom, scale, pan) are applied
     * by pre-processing the cached bitmap and calling switchSource. We never call
     * VcplaxEngine.setRotation for images because it can destabilise the vcplax
     * Binder process and disconnect the injection.
     *
     * For video: rotation/mirror are applied via Binder (vcplax handles the video
     * stream internally); spatial transforms (zoom/pan/scale) are skipped.
     *
     * IMPORTANT: This loop NEVER restarts injection — that would reset vcplax's
     * playback position and produce the "loops every second" symptom.
     */
    private suspend fun monitorVcplaxTransforms() = withContext(Dispatchers.IO) {
        // For video mode: pre-sync tracking vars to current values so that
        // setRotation/setMirror are NOT called at startup with default values.
        // A Binder call to setRotation immediately after start() destabilises
        // vcplax and disconnects the injection. Only call when user actively changes
        // rotation/mirror via the float window controls.
        if (isVideo) {
            lastRenderedRotation = rotation
            lastRenderedMirror   = mirror
            lastRenderedZoom     = zoomFactor
            lastRenderedScale    = frameFillScale
            lastRenderedPanX     = panX
            lastRenderedPanY     = panY
        } else {
            resetLastRendered()
        }

        // Cache the raw bitmap for image mode so re-renders don't reload from disk
        val cachedBitmap: Bitmap? = if (!isVideo) loadBitmapForInjection(mediaPath) else null

        // Force an initial render so the first frame is correct
        var firstRender = true

        try {
            while (running) {
                val z  = zoomFactor;  val s  = frameFillScale
                val px = panX;        val py = panY
                val r  = rotation;    val m  = mirror

                if (firstRender || !transformsMatch(z, s, px, py, r, m)) {
                    if (!isVideo) {
                        // Apply ALL transforms (rotation + mirror + zoom + scale + pan) by
                        // pre-processing the cached bitmap and switching the source.
                        // This avoids calling VcplaxEngine.setRotation which can crash vcplax.
                        if (cachedBitmap != null) {
                            val transformed = applyTransformsWithValues(cachedBitmap, r, m, z, s, px, py)
                            val tp = writeTempBitmap(transformed)
                            if (transformed !== cachedBitmap) transformed.recycle()
                            if (tp != null) {
                                try {
                                    VcplaxEngine.getProxy()?.switchSource(tp, 1)
                                } catch (e: Exception) {
                                    Log.w(TAG, "switchSource failed: ${e.message}")
                                }
                            }
                        }
                    } else {
                        // For video: apply rotation/mirror via Binder only when changed.
                        // Zoom/pan/scale are not applied in video mode (vcplax handles the stream).
                        // Only send Binder calls when values actually changed.
                        // Never call on startup — setRotation(0) immediately after
                        // start() disconnects the vcplax injection.
                        if (r != lastRenderedRotation) {
                            VcplaxEngine.setRotation(r)
                        }
                        if (m != lastRenderedMirror) {
                            VcplaxEngine.setMirror(m)
                        }
                    }

                    firstRender = false
                    updateLastRendered(z, s, px, py, r, m)
                }

                delay(RENDER_POLL_MS)
            }
        } finally {
            cachedBitmap?.recycle()
        }
    }

    // ── Legacy injection (kept as fallback) ────────────────────────────────

    private suspend fun legacyInject() {
        setupInjectLib()
        tryLoadV4L2Module()
        val devices = RootManager.getVideoDevices()
        setupLdPreload()

        if (devices.isNotEmpty()) {
            val device = devices.last()
            val started = tryStartV4L2(device)
            if (started) { streamFramesToV4L2(device); return }
        }
        streamFramesToSharedFile()
    }

    private fun setupInjectLib() {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val srcLib    = File(nativeDir, "libvcam_inject.so")

        RootManager.runCommands("mkdir -p $VCAM_DIR", "chmod 777 $VCAM_DIR")

        if (srcLib.exists()) {
            RootManager.runCommands(
                "cp '${srcLib.absolutePath}' $INJECT_LIB",
                "chmod 755 $INJECT_LIB",
                "chown root:root $INJECT_LIB 2>/dev/null || true"
            )
        }
    }

    private fun setupLdPreload() {
        val propVal = "LD_PRELOAD=$INJECT_LIB"
        RootManager.runCommand("setenforce 0 2>/dev/null || true")

        listOf(
            "wrap.cameraserver",
            "wrap.android.hardware.camera.provider@2.4-service",
            "wrap.android.hardware.camera.provider@2.5-service",
            "wrap.android.hardware.camera.provider@2.6-service",
            "vendor.camera.hal.vendor"
        ).forEach { prop ->
            RootManager.runCommand("setprop '$prop' '$propVal'")
            RootManager.runCommand("resetprop '$prop' '$propVal' 2>/dev/null || true")
        }

        if (!targetPackage.isNullOrBlank()) {
            val wrapProp = "wrap.$targetPackage"
            RootManager.runCommand("setprop '$wrapProp' '$propVal'")
            RootManager.runCommand("resetprop '$wrapProp' '$propVal' 2>/dev/null || true")
        }

        RootManager.runCommands("setprop ctl.restart cameraserver", "sleep 1")
        if (!targetPackage.isNullOrBlank()) {
            RootManager.runCommand("am force-stop '$targetPackage'")
        }
    }

    private fun cleanupAllWrapProps() {
        listOf(
            "wrap.cameraserver",
            "wrap.android.hardware.camera.provider@2.4-service",
            "wrap.android.hardware.camera.provider@2.5-service",
            "wrap.android.hardware.camera.provider@2.6-service",
            "vendor.camera.hal.vendor"
        ).forEach { prop ->
            RootManager.runCommand("setprop '$prop' '' 2>/dev/null || true")
            RootManager.runCommand("resetprop --delete '$prop' 2>/dev/null || true")
        }
        if (!targetPackage.isNullOrBlank()) {
            RootManager.runCommand("setprop 'wrap.$targetPackage' '' 2>/dev/null || true")
            RootManager.runCommand("resetprop --delete 'wrap.$targetPackage' 2>/dev/null || true")
        }
    }

    private fun tryLoadV4L2Module() {
        RootManager.runCommands(
            "modprobe v4l2loopback devices=1 video_nr=10 card_label=VCam exclusive_caps=1 2>/dev/null || true",
            "insmod /vendor/lib/modules/v4l2loopback.ko   devices=1 2>/dev/null || true",
            "insmod /system/lib/modules/v4l2loopback.ko   devices=1 2>/dev/null || true",
            "insmod /system/lib64/modules/v4l2loopback.ko devices=1 2>/dev/null || true"
        )
    }

    private fun tryStartV4L2(device: String): Boolean {
        return try { nativeStartFrameLoop(TARGET_W, TARGET_H, device) }
        catch (e: Exception) { Log.e(TAG, "v4l2 start: ${e.message}"); false }
    }

    private suspend fun streamFramesToV4L2(device: String) {
        if (isVideo) streamVideo() else streamImage()
    }

    private suspend fun streamFramesToSharedFile() {
        if (isVideo) streamVideo() else streamImage()
    }

    /**
     * Real-time image streaming: re-renders whenever any transform parameter changes.
     * The raw bitmap is loaded ONCE at startup, then re-transformed on every change.
     */
    private suspend fun streamImage() = withContext(Dispatchers.IO) {
        val rawBitmap = loadBitmapForInjection(mediaPath)
        if (rawBitmap == null) {
            Log.e(TAG, "Cannot load image: $mediaPath"); return@withContext
        }

        resetLastRendered()
        var firstRender = true

        try {
            while (running) {
                val z  = zoomFactor; val s  = frameFillScale
                val px = panX;       val py = panY
                val r  = rotation;   val m  = mirror

                if (firstRender || !transformsMatch(z, s, px, py, r, m)) {
                    firstRender = false
                    updateLastRendered(z, s, px, py, r, m)
                    val transformed = applyTransformsWithValues(rawBitmap, r, m, z, s, px, py)
                    val yuyv = bitmapToYUYV(transformed, TARGET_W, TARGET_H)
                    if (transformed !== rawBitmap) transformed.recycle()
                    nativeUpdateYUYVFrame(yuyv, TARGET_W, TARGET_H)
                }

                delay(RENDER_POLL_MS)
            }
        } finally {
            rawBitmap.recycle()
        }
    }

    private suspend fun streamVideo() = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(mediaPath)
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 5000L

            var posMs = 0L
            val frameIntervalMs = 33L

            while (running) {
                // OPTION_PREVIOUS_SYNC: always returns the sync frame at or BEFORE
                // posMs, giving monotonically advancing playback.
                // OPTION_CLOSEST_SYNC could jump to a *future* keyframe for positions
                // between two keyframes, making the video appear to skip forward and
                // restart — producing the "plays ~1 second then loops" symptom in
                // the legacy fallback path.
                val frameBitmap = retriever.getFrameAtTime(
                    posMs * 1000L, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC
                )
                if (frameBitmap != null) {
                    val transformed = applyTransforms(frameBitmap)
                    val yuyv = bitmapToYUYV(transformed, TARGET_W, TARGET_H)
                    if (transformed !== frameBitmap) transformed.recycle()
                    frameBitmap.recycle()
                    nativeUpdateYUYVFrame(yuyv, TARGET_W, TARGET_H)
                }
                posMs += frameIntervalMs
                if (posMs >= durationMs) posMs = 0L
                delay(frameIntervalMs)
            }
        } finally { retriever.release() }
    }

    // ── Transform helpers ───────────────────────────────────────────────────

    private fun resetLastRendered() {
        lastRenderedZoom     = Float.MIN_VALUE
        lastRenderedScale    = Float.MIN_VALUE
        lastRenderedPanX     = Int.MIN_VALUE
        lastRenderedPanY     = Int.MIN_VALUE
        lastRenderedRotation = Int.MIN_VALUE
        lastRenderedMirror   = !mirror // force first render
    }

    private fun transformsMatch(z: Float, s: Float, px: Int, py: Int, r: Int, m: Boolean): Boolean =
        z == lastRenderedZoom && s == lastRenderedScale &&
        px == lastRenderedPanX && py == lastRenderedPanY &&
        r == lastRenderedRotation && m == lastRenderedMirror

    private fun updateLastRendered(z: Float, s: Float, px: Int, py: Int, r: Int, m: Boolean) {
        lastRenderedZoom     = z;  lastRenderedScale    = s
        lastRenderedPanX     = px; lastRenderedPanY     = py
        lastRenderedRotation = r;  lastRenderedMirror   = m
    }

    // ── Bitmap loaders ──────────────────────────────────────────────────────

    /**
     * Load bitmap at the minimum sample size that still gives >= TARGET_W x TARGET_H pixels.
     * This avoids OOM on high-resolution source images while maximising quality
     * for the 1280×720 camera output.
     */
    private fun loadBitmapForInjection(path: String): Bitmap? {
        return try {
            // Step 1: decode bounds only (no pixel allocation)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)

            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Log.e(TAG, "Cannot determine image dimensions: $path")
                return null
            }

            // Step 2: find smallest power-of-2 sample that keeps >= TARGET dimensions
            var sample = 1
            while ((bounds.outWidth  / (sample * 2)) >= TARGET_W &&
                   (bounds.outHeight / (sample * 2)) >= TARGET_H) {
                sample *= 2
            }

            // Step 3: decode at chosen sample size
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = sample
            }
            Log.d(TAG, "loadBitmapForInjection: ${bounds.outWidth}x${bounds.outHeight} → sample=$sample")
            BitmapFactory.decodeFile(path, opts)
        } catch (e: Exception) {
            Log.e(TAG, "loadBitmapForInjection failed: ${e.message}")
            null
        }
    }

    private fun loadAndTransformBitmap(path: String): Bitmap? {
        val raw = loadBitmapForInjection(path) ?: return null
        return applyTransforms(raw)
    }

    /**
     * Write a transformed bitmap to a temp JPEG file so vcplax can load it via switchSource.
     * Uses a unique filename per render to prevent vcplax from serving stale cached content.
     */
    private fun writeTempBitmap(bmp: Bitmap): String? {
        return try {
            // Ensure the bitmap is opaque — JPEG doesn't support alpha
            val opaque = if (bmp.hasAlpha()) {
                val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.RGB_565)
                Canvas(out).apply {
                    drawARGB(255, 0, 0, 0)
                    drawBitmap(bmp, 0f, 0f, null)
                }
                out
            } else bmp

            val dir = File(context.cacheDir, "vcam_tmp").also { it.mkdirs() }
            // Unique filename per render so vcplax doesn't cache stale content
            val f   = File(dir, "transformed_frame_${System.nanoTime()}.jpg")
            java.io.FileOutputStream(f).use { out ->
                opaque.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            if (opaque !== bmp) opaque.recycle()
            // Keep only the latest 3 temp files to avoid filling storage
            try {
                dir.listFiles()
                    ?.filter { it.name.startsWith("transformed_frame_") && it.name != f.name }
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(3)
                    ?.forEach { it.delete() }
            } catch (_: Exception) {}
            f.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "writeTempBitmap failed: ${e.message}")
            null
        }
    }

    // ── Core transform pipeline ─────────────────────────────────────────────

    /**
     * Apply current transforms (reads @Volatile fields).
     */
    private fun applyTransforms(src: Bitmap): Bitmap =
        applyTransformsWithValues(src, rotation, mirror, zoomFactor, frameFillScale, panX, panY)

    /**
     * Apply explicit transform values — used for real-time updates where params
     * are captured before the loop body so they're consistent within one frame.
     *
     * Pipeline:
     *   1. Rotation + mirror  → stage1
     *   2. Digital zoom + pan → stage2
     *   3. Frame-fill scale   → stage3  (output = TARGET_W × TARGET_H canvas)
     */
    private fun applyTransformsWithValues(
        src: Bitmap,
        rot: Int, mir: Boolean,
        zoom: Float, fillScale: Float,
        pX: Int, pY: Int
    ): Bitmap {
        // Stage 1: rotation + mirror
        val stage1: Bitmap = if (rot != 0 || mir) {
            val m = Matrix()
            if (rot != 0) m.postRotate(rot.toFloat())
            if (mir) m.postScale(-1f, 1f, src.width / 2f, src.height / 2f)
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        } else src

        // Stage 2: digital zoom (crop)
        val z  = zoom.coerceIn(1f, 5f)
        val stage2: Bitmap = if (z != 1f) {
            val cropW = (stage1.width  / z).toInt().coerceAtLeast(1)
            val cropH = (stage1.height / z).toInt().coerceAtLeast(1)
            val left  = ((stage1.width  - cropW) / 2).coerceIn(0, (stage1.width  - cropW).coerceAtLeast(0))
            val top   = ((stage1.height - cropH) / 2).coerceIn(0, (stage1.height - cropH).coerceAtLeast(0))
            val cropped = Bitmap.createBitmap(stage1, left, top, cropW, cropH)
            if (stage1 !== src) stage1.recycle()
            cropped
        } else stage1

        // Stage 3: frame-fill scale + free pan (output fixed at TARGET_W × TARGET_H)
        val scale  = fillScale.coerceIn(0.1f, 2f)
        val stage3: Bitmap = if (scale != 1f || pX != 0 || pY != 0) {
            val scaledW = (TARGET_W * scale).toInt().coerceAtLeast(1)
            val scaledH = (TARGET_H * scale).toInt().coerceAtLeast(1)
            val scaled  = Bitmap.createScaledBitmap(stage2, scaledW, scaledH, true)
            if (stage2 !== src) stage2.recycle()

            val drawX = (TARGET_W  - scaledW) / 2f + pX
            val drawY = (TARGET_H  - scaledH) / 2f + pY

            val canvas = Bitmap.createBitmap(TARGET_W, TARGET_H, Bitmap.Config.RGB_565)
            Canvas(canvas).apply {
                drawARGB(255, 0, 0, 0)
                drawBitmap(scaled, drawX, drawY, null)
            }
            scaled.recycle()
            canvas
        } else stage2

        return stage3
    }

    private fun bitmapToYUYV(src: Bitmap, outW: Int, outH: Int): ByteArray {
        val bmp    = if (src.width != outW || src.height != outH)
                         Bitmap.createScaledBitmap(src, outW, outH, true) else src
        val pixels = IntArray(outW * outH)
        bmp.getPixels(pixels, 0, outW, 0, 0, outW, outH)
        if (bmp !== src) bmp.recycle()

        // Full-range (0-255) BT.601 RGB→YUYV conversion.
        // Matches V4L2_COLORSPACE_SRGB set on the device so the consumer
        // interprets the YUV values as full-range and colours stay accurate.
        val yuyv = ByteArray(outW * outH * 2)
        var idx = 0; var pi = 0
        while (pi < pixels.size - 1) {
            val p0 = pixels[pi]; val p1 = pixels[pi + 1]
            val r0 = (p0 shr 16) and 0xff; val g0 = (p0 shr 8) and 0xff; val b0 = p0 and 0xff
            val r1 = (p1 shr 16) and 0xff; val g1 = (p1 shr 8) and 0xff; val b1 = p1 and 0xff
            // Full-range BT.601: Y = (77R + 150G + 29B) / 256
            val y0 = (77*r0 + 150*g0 + 29*b0) shr 8
            val y1 = (77*r1 + 150*g1 + 29*b1) shr 8
            // U = (-43R - 85G + 128B) / 256 + 128, V = (128R - 107G - 21B) / 256 + 128
            val u  = (((-43*r0 - 85*g0 + 128*b0) shr 8) + 128)
            val v  = (((128*r0 - 107*g0 - 21*b0) shr 8) + 128)
            yuyv[idx++] = y0.coerceIn(0, 255).toByte()
            yuyv[idx++] = u.coerceIn(0, 255).toByte()
            yuyv[idx++] = y1.coerceIn(0, 255).toByte()
            yuyv[idx++] = v.coerceIn(0, 255).toByte()
            pi += 2
        }
        return yuyv
    }
}
