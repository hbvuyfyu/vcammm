package com.vcam.utils

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel

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
        private const val TX_SET_RANGE      = 22
        private const val TX_PAUSE_RESUME   = 25
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
