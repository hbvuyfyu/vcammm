package com.vcam.utils

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.util.Log

/**
 * Binder proxy for IMyBinderService implemented by vcplax.
 * Transaction codes reverse-engineered from the reference APK (d1/f.java).
 */
class VcamBinderProxy(private val binder: IBinder) : IInterface {

    companion object {
        const val DESCRIPTOR = "com.xiaomi.vlive.IMyBinderService"

        // Transaction codes
        private const val TX_START          = 11
        private const val TX_STOP           = 12
        private const val TX_GET_CAMERA_IDS = 13
        private const val TX_SWITCH_SOURCE  = 14
        private const val TX_GET_STATUS     = 15
        private const val TX_SET_MIRROR     = 16
        private const val TX_SET_AUTO_ROTATE = 17
        private const val TX_SET_ROTATION   = 18
        private const val TX_SET_LOOP       = 19
        // TX_SET_RANGE: the exact code is uncertain (reverse-engineered).
        // Candidates 20, 21, 22 are all tried via setRangeBroadcast().
        private const val TX_SET_RANGE      = 22
        private const val TX_SET_RANGE_20   = 20   // most likely alternative
        private const val TX_SET_RANGE_21   = 21   // second alternative
        private const val TX_PAUSE_RESUME   = 25

        private const val PROXY_TAG = "VcamBinderProxy"
    }

    override fun asBinder(): IBinder = binder

    /**
     * Start injection.
     * @param url  local file path (mp4/jpg/png) or rtmp:// URL
     * @param autoRotate  auto-rotate to match device orientation
     * @param loop  loop video playback
     */
    fun start(url: String, autoRotate: Boolean = false, loop: Boolean = true): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(url)
            data.writeInt(if (autoRotate) 1 else 0)
            data.writeInt(if (loop) 1 else 0)
            binder.transact(TX_START, data, reply, 0)
            reply.readException()
            reply.readInt()
        } finally {
            reply.recycle(); data.recycle()
        }
    }

    /**
     * Alternative start that sends (url, loop) WITHOUT the autoRotate field.
     *
     * Some vcplax builds define start() as start(url, loop) — two args — while
     * others use start(url, autoRotate, loop) — three args.  When the two-arg
     * variant is active, our three-arg start() above writes autoRotate (= 0 =
     * false) into the position vcplax reads as 'loop', so the video plays once
     * without looping and vcplax then stops.  This variant skips autoRotate
     * entirely so 'loop' lands in the correct position.
     */
    fun startLoopOnly(url: String, loop: Boolean = true): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(url)
            data.writeInt(if (loop) 1 else 0)
            binder.transact(TX_START, data, reply, 0)
            reply.readException()
            reply.readInt()
        } finally {
            reply.recycle(); data.recycle()
        }
    }

    /**
     * Broadcast setRange across every candidate transaction code.
     *
     * The exact TX code for setRange was reverse-engineered and may be off.
     * Codes 20, 21, and 22 are all tried in sequence so at least one reaches
     * the correct handler regardless of which build of vcplax is installed.
     * Each call is individually guarded; a failure on the wrong code is silent.
     *
     * IMPORTANT: call this BEFORE start() so vcplax applies the range before
     * the first frame is decoded.  A post-start call is a belt-and-suspenders
     * fallback for builds that accept it after start.
     *
     * @param startUs  start position in microseconds (0 = beginning)
     * @param endUs    end position in microseconds (0 = full length sentinel)
     */
    fun setRangeBroadcast(startUs: Long, endUs: Long) {
        for (txCode in listOf(TX_SET_RANGE_20, TX_SET_RANGE_21, TX_SET_RANGE)) {
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeLong(startUs)
                data.writeLong(endUs)
                binder.transact(txCode, data, reply, 0)
                reply.readException()
                reply.readInt()
                Log.d(PROXY_TAG, "setRangeBroadcast TX$txCode OK (start=$startUs end=$endUs)")
            } catch (e: Exception) {
                Log.w(PROXY_TAG, "setRangeBroadcast TX$txCode: ${e.message}")
            } finally { reply.recycle(); data.recycle() }
        }
    }

    /** Stop injection and restore normal camera. */
    fun stop(): Int {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            binder.transact(TX_STOP, data, reply, 0)
            reply.readException(); reply.readInt()
        } finally { reply.recycle(); data.recycle() }
    }

    /** Returns available camera IDs (front/back). */
    fun getCameraIds(): IntArray? {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            binder.transact(TX_GET_CAMERA_IDS, data, reply, 0)
            reply.readException(); reply.createIntArray()
        } finally { reply.recycle(); data.recycle() }
    }

    /** Switch video/image source while injection is running. */
    fun switchSource(url: String, type: Int = 1): Int {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(type); data.writeString(url)
            binder.transact(TX_SWITCH_SOURCE, data, reply, 0)
            reply.readException(); reply.readInt()
        } finally { reply.recycle(); data.recycle() }
    }

    /**
     * Get injection status.
     * @return 5 = actively injecting, other = stopped/error
     */
    fun getStatus(): Int {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            binder.transact(TX_GET_STATUS, data, reply, 0)
            reply.readException(); reply.readInt()
        } finally { reply.recycle(); data.recycle() }
    }

    fun setMirror(enabled: Boolean): Int {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(if (enabled) 1 else 0)
            binder.transact(TX_SET_MIRROR, data, reply, 0)
            reply.readException(); reply.readInt()
        } finally { reply.recycle(); data.recycle() }
    }

    fun setAutoRotate(enabled: Boolean): Int {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(if (enabled) 1 else 0)
            binder.transact(TX_SET_AUTO_ROTATE, data, reply, 0)
            reply.readException(); reply.readInt()
        } finally { reply.recycle(); data.recycle() }
    }

    /** Set rotation in degrees (0, 90, 180, 270). */
    fun setRotation(degrees: Int): Int {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(degrees)
            binder.transact(TX_SET_ROTATION, data, reply, 0)
            reply.readException(); reply.readInt()
        } finally { reply.recycle(); data.recycle() }
    }

    fun setLoop(enabled: Boolean): Int {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(if (enabled) 1 else 0)
            binder.transact(TX_SET_LOOP, data, reply, 0)
            reply.readException(); reply.readInt()
        } finally { reply.recycle(); data.recycle() }
    }

    /**
     * Set video playback range (microseconds).
     * @param startUs  start position in microseconds (0 = beginning)
     * @param endUs    end position in microseconds (0 = full length)
     */
    fun setRange(startUs: Long, endUs: Long): Int {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeLong(startUs); data.writeLong(endUs)
            binder.transact(TX_SET_RANGE, data, reply, 0)
            reply.readException(); reply.readInt()
        } finally { reply.recycle(); data.recycle() }
    }

    /** Toggle pause/resume. */
    fun pauseResume(): Int {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            binder.transact(TX_PAUSE_RESUME, data, reply, 0)
            reply.readException(); reply.readInt()
        } finally { reply.recycle(); data.recycle() }
    }

    /** True when vcplax is actively injecting (status == 5). */
    val isRunning: Boolean
        get() = try { getStatus() == 5 } catch (_: Exception) { false }
}
