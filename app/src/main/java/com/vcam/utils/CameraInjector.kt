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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * CameraInjector — system-wide camera replacement for Magisk-rooted Android.
 *
 * Strategy (tried in order):
 *   1. v4l2loopback — loads kernel module, writes real frames to /dev/videoX.
 *      Works system-wide for ALL apps transparently.
 *   2. LD_PRELOAD via wrap.cameraserver + wrap.<pkg> — hooks camera HAL inside
 *      the camera server process and/or the target app process.
 *      Reads frames from /data/local/tmp/vcam/frame.yuyv (written by us).
 *
 * Frame pipeline (Kotlin side):
 *   Image → BitmapFactory → scale to TARGET_W×TARGET_H
 *        → bitmapToYUYV() → nativeUpdateYUYVFrame() + v4l2 write
 *   Video → MediaMetadataRetriever (frame-by-frame) → same pipeline at 30 fps
 */
class CameraInjector(
    private val context: Context,
    private val mediaPath: String,
    private val isVideo: Boolean,
    private val targetPackage: String?,
    var rotation: Int = 0,   // 0 / 90 / 180 / 270
    var mirror: Boolean = false
) {
    companion object {
        private const val TAG        = "CameraInjector"
        private const val VCAM_DIR   = "/data/local/tmp/vcam"
        private const val INJECT_LIB = "/data/local/tmp/libvcam_inject.so"
        private const val FRAME_FILE = "$VCAM_DIR/frame.yuyv"
        private const val META_FILE  = "$VCAM_DIR/frame_info"

        const val TARGET_W = 1280
        const val TARGET_H = 720

        init {
            try { System.loadLibrary("vcam_native") }
            catch (e: UnsatisfiedLinkError) { Log.w(TAG, "vcam_native: ${e.message}") }
        }

        /** Start the v4l2 frame-pump loop on the given device. */
        @JvmStatic external fun nativeStartFrameLoop(width: Int, height: Int, videoDevice: String): Boolean
        /** Push a new YUYV frame (also writes shared file). */
        @JvmStatic external fun nativeUpdateYUYVFrame(yuyvData: ByteArray, width: Int, height: Int)
        /** Stop all injection. */
        @JvmStatic external fun nativeStopInjection()
        /** Check whether a v4l2 device supports video-output. */
        @JvmStatic external fun nativeCheckDevice(videoDevice: String): Boolean

        // Legacy stubs — kept so existing callers compile
        @JvmStatic external fun nativeInjectImage(imagePath: String, videoDevice: String): Int
        @JvmStatic external fun nativeInjectVideo(videoPath: String, videoDevice: String): Int
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    @Volatile private var running = false
    private var injectionJob: Job? = null

    // ── Public API ────────────────────────────────────────────────────

    fun start() {
        running = true
        injectionJob = scope.launch { performInjection() }
    }

    fun stop() {
        running = false
        injectionJob?.cancel()

        // Clean up LD_PRELOAD properties
        cleanupAllWrapProps()

        try { nativeStopInjection() } catch (_: Exception) {}

        // Kill helper processes
        RootManager.runCommands(
            "pkill -f ffmpeg 2>/dev/null || true",
            "pkill -f v4l2  2>/dev/null || true"
        )
        // Restart cameraserver so normal camera works again
        RootManager.runCommand("setprop ctl.restart cameraserver")
        Log.d(TAG, "VCam stopped")
    }

    // ── Core injection pipeline ───────────────────────────────────────

    private suspend fun performInjection() {
        Log.d(TAG, "performInjection: isVideo=$isVideo target=$targetPackage")

        // 1. Push the inject library and set up the shared directory
        setupInjectLib()

        // 2. Load v4l2loopback module
        tryLoadV4L2Module()
        val devices = RootManager.getVideoDevices()

        // 3. Start the LD_PRELOAD injection (sets up props + restarts procs)
        setupLdPreload()

        // 4. Stream real frames
        if (devices.isNotEmpty()) {
            val device = devices.last()
            Log.d(TAG, "v4l2 device: $device")
            val started = tryStartV4L2(device)
            if (started) {
                streamFramesToV4L2(device)
                return
            }
        }

        // Fallback: just write to shared file (LD_PRELOAD inject lib reads it)
        streamFramesToSharedFile()
    }

    // ── Library + directory setup ─────────────────────────────────────

    private fun setupInjectLib() {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val srcLib = File(nativeDir, "libvcam_inject.so")

        RootManager.runCommands(
            "mkdir -p $VCAM_DIR",
            "chmod 777 $VCAM_DIR"
        )

        if (srcLib.exists()) {
            RootManager.runCommands(
                "cp '${srcLib.absolutePath}' $INJECT_LIB",
                "chmod 755 $INJECT_LIB",
                "chown root:root $INJECT_LIB 2>/dev/null || true"
            )
            Log.d(TAG, "Inject lib pushed: $INJECT_LIB")
        } else {
            Log.e(TAG, "libvcam_inject.so not found in $nativeDir")
        }
    }

    // ── LD_PRELOAD injection (system-wide via cameraserver wrap) ──────

    private fun setupLdPreload() {
        val propVal = "LD_PRELOAD=$INJECT_LIB"

        // Disable SELinux temporarily (Magisk permissive mode may already do this)
        RootManager.runCommand("setenforce 0 2>/dev/null || true")

        // System-wide: inject into cameraserver and camera provider HAL
        val systemTargets = listOf(
            "wrap.cameraserver",
            "wrap.android.hardware.camera.provider@2.4-service",
            "wrap.android.hardware.camera.provider@2.5-service",
            "wrap.android.hardware.camera.provider@2.6-service",
            "vendor.camera.hal.vendor"
        )
        for (prop in systemTargets) {
            RootManager.runCommand("setprop '$prop' '$propVal'")
            RootManager.runCommand("resetprop '$prop' '$propVal' 2>/dev/null || true")
        }

        // Per-app injection if target specified
        if (!targetPackage.isNullOrBlank()) {
            val wrapProp = "wrap.$targetPackage"
            RootManager.runCommand("setprop '$wrapProp' '$propVal'")
            RootManager.runCommand("resetprop '$wrapProp' '$propVal' 2>/dev/null || true")
        }

        // Restart cameraserver so the wrap prop takes effect
        RootManager.runCommands(
            "setprop ctl.restart cameraserver",
            "sleep 1"
        )

        // Force-stop and relaunch target app if specified
        if (!targetPackage.isNullOrBlank()) {
            RootManager.runCommand("am force-stop '$targetPackage'")
        }

        Log.d(TAG, "LD_PRELOAD injection set up")
    }

    private fun cleanupAllWrapProps() {
        val systemTargets = listOf(
            "wrap.cameraserver",
            "wrap.android.hardware.camera.provider@2.4-service",
            "wrap.android.hardware.camera.provider@2.5-service",
            "wrap.android.hardware.camera.provider@2.6-service",
            "vendor.camera.hal.vendor"
        )
        for (prop in systemTargets) {
            RootManager.runCommand("setprop '$prop' '' 2>/dev/null || true")
            RootManager.runCommand("resetprop --delete '$prop' 2>/dev/null || true")
        }
        if (!targetPackage.isNullOrBlank()) {
            val wp = "wrap.$targetPackage"
            RootManager.runCommand("setprop '$wp' '' 2>/dev/null || true")
            RootManager.runCommand("resetprop --delete '$wp' 2>/dev/null || true")
        }
    }

    // ── v4l2 ─────────────────────────────────────────────────────────

    private fun tryLoadV4L2Module() {
        RootManager.runCommands(
            "modprobe v4l2loopback devices=1 video_nr=10 card_label=VCam exclusive_caps=1 2>/dev/null || true",
            "insmod /vendor/lib/modules/v4l2loopback.ko   devices=1 2>/dev/null || true",
            "insmod /system/lib/modules/v4l2loopback.ko   devices=1 2>/dev/null || true",
            "insmod /system/lib64/modules/v4l2loopback.ko devices=1 2>/dev/null || true"
        )
    }

    private fun tryStartV4L2(device: String): Boolean {
        return try {
            nativeStartFrameLoop(TARGET_W, TARGET_H, device)
        } catch (e: Exception) {
            Log.e(TAG, "v4l2 start failed: ${e.message}")
            false
        }
    }

    // ── Frame streaming ───────────────────────────────────────────────

    /**
     * Stream frames to v4l2 device AND shared file.
     * For images: one conversion, then loop at 30fps.
     * For videos: extract frames at ~30fps, convert, push.
     */
    private suspend fun streamFramesToV4L2(device: String) {
        if (isVideo) streamVideo(pushToV4L2 = true)
        else          streamImage(pushToV4L2 = true)
    }

    private suspend fun streamFramesToSharedFile() {
        if (isVideo) streamVideo(pushToV4L2 = false)
        else          streamImage(pushToV4L2 = false)
    }

    private suspend fun streamImage(pushToV4L2: Boolean) = withContext(Dispatchers.IO) {
        val bitmap = loadAndTransformBitmap(mediaPath) ?: run {
            Log.e(TAG, "Cannot load image: $mediaPath"); return@withContext
        }
        val yuyv = bitmapToYUYV(bitmap, TARGET_W, TARGET_H)
        bitmap.recycle()

        Log.d(TAG, "Image loaded → YUYV ${TARGET_W}×${TARGET_H}, ${yuyv.size} bytes")

        // Push once; the v4l2 pump loop keeps writing it
        nativeUpdateYUYVFrame(yuyv, TARGET_W, TARGET_H)

        // Stay alive as long as injection is running
        while (running && isActive) delay(500)
    }

    private suspend fun streamVideo(pushToV4L2: Boolean) = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(mediaPath)
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 5000L

            Log.d(TAG, "Video duration: ${durationMs}ms")

            var posMs = 0L
            val frameIntervalMs = 33L // ~30fps

            while (running && isActive) {
                val frameBitmap = retriever.getFrameAtTime(
                    posMs * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )

                if (frameBitmap != null) {
                    val transformed = applyTransforms(frameBitmap)
                    val yuyv = bitmapToYUYV(transformed, TARGET_W, TARGET_H)
                    if (transformed !== frameBitmap) transformed.recycle()
                    frameBitmap.recycle()

                    nativeUpdateYUYVFrame(yuyv, TARGET_W, TARGET_H)
                }

                posMs += frameIntervalMs
                if (posMs >= durationMs) posMs = 0L // loop

                delay(frameIntervalMs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Video stream error: ${e.message}")
        } finally {
            retriever.release()
        }
    }

    // ── Bitmap helpers ────────────────────────────────────────────────

    private fun loadAndTransformBitmap(path: String): Bitmap? {
        val raw = try {
            BitmapFactory.decodeFile(path) ?: return null
        } catch (e: Exception) {
            Log.e(TAG, "BitmapFactory failed: ${e.message}"); return null
        }
        return applyTransforms(raw)
    }

    private fun applyTransforms(src: Bitmap): Bitmap {
        val needsRotate = rotation != 0
        val needsMirror = mirror
        if (!needsRotate && !needsMirror) return src

        val matrix = Matrix()
        if (needsRotate) matrix.postRotate(rotation.toFloat())
        if (needsMirror) matrix.postScale(-1f, 1f, src.width / 2f, src.height / 2f)

        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    /**
     * Convert any-size Bitmap → YUYV ByteArray at [outW]×[outH].
     * YUYV = 2 bytes per pixel, packed Y0 U0 Y1 V0.
     */
    private fun bitmapToYUYV(src: Bitmap, outW: Int, outH: Int): ByteArray {
        // Scale if needed
        val bmp = if (src.width != outW || src.height != outH) {
            Bitmap.createScaledBitmap(src, outW, outH, true)
        } else src

        val pixels = IntArray(outW * outH)
        bmp.getPixels(pixels, 0, outW, 0, 0, outW, outH)
        if (bmp !== src) bmp.recycle()

        val yuyv = ByteArray(outW * outH * 2)
        var idx = 0
        var pi  = 0
        while (pi < pixels.size - 1) {
            val p0 = pixels[pi]
            val p1 = pixels[pi + 1]

            val r0 = (p0 shr 16) and 0xff
            val g0 = (p0 shr  8) and 0xff
            val b0 =  p0         and 0xff
            val r1 = (p1 shr 16) and 0xff
            val g1 = (p1 shr  8) and 0xff
            val b1 =  p1         and 0xff

            val y0 = ((66 * r0 + 129 * g0 + 25 * b0 + 128) shr 8) + 16
            val y1 = ((66 * r1 + 129 * g1 + 25 * b1 + 128) shr 8) + 16
            val u  = ((-38 * r0 - 74 * g0 + 112 * b0 + 128) shr 8) + 128
            val v  = ((112 * r0 - 94 * g0 - 18 * b0 + 128) shr 8) + 128

            yuyv[idx++] = y0.coerceIn(16, 235).toByte()
            yuyv[idx++] = u .coerceIn(16, 240).toByte()
            yuyv[idx++] = y1.coerceIn(16, 235).toByte()
            yuyv[idx++] = v .coerceIn(16, 240).toByte()

            pi += 2
        }
        return yuyv
    }
}
