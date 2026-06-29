package com.vcam.utils

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * Manages 5 media slots (1-4 = image, 5 = video).
 * Files are copied to internal storage so they persist and are accessible by root.
 */
object MediaSlotManager {

    private const val PREFS = "vcam_slots_v2"

    /** Copy URI content to internal slot file and save the path. */
    fun setSlot(context: Context, slot: Int, uri: Uri, isVideo: Boolean) {
        val ext  = if (isVideo) "mp4" else "jpg"
        val dest = slotFile(context, slot, ext)
        context.contentResolver.openInputStream(uri)?.use { ins ->
            FileOutputStream(dest).use { out -> ins.copyTo(out) }
        }
        prefs(context).edit()
            .putString("path_$slot", dest.absolutePath)
            .putBoolean("video_$slot", isVideo)
            .apply()
    }

    fun getSlotPath(context: Context, slot: Int): String? =
        prefs(context).getString("path_$slot", null)?.let {
            if (File(it).exists()) it else null
        }

    fun isSlotVideo(context: Context, slot: Int): Boolean =
        prefs(context).getBoolean("video_$slot", slot == 5)

    fun isSlotSet(context: Context, slot: Int): Boolean =
        getSlotPath(context, slot) != null

    fun clearSlot(context: Context, slot: Int) {
        val path = prefs(context).getString("path_$slot", null)
        path?.let { File(it).delete() }
        prefs(context).edit().remove("path_$slot").remove("video_$slot").apply()
    }

    /** Get first-frame thumbnail for the slot (null if not set or on error). */
    fun getThumbnail(context: Context, slot: Int): Bitmap? {
        val path = getSlotPath(context, slot) ?: return null
        return if (isSlotVideo(context, slot)) {
            try {
                MediaMetadataRetriever().run {
                    setDataSource(path)
                    val bmp = getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    release()
                    bmp
                }
            } catch (_: Exception) { null }
        } else {
            android.graphics.BitmapFactory.decodeFile(path)
        }
    }

    private fun slotFile(context: Context, slot: Int, ext: String): File {
        val dir = File(context.filesDir, "slots").also { it.mkdirs() }
        return File(dir, "slot_$slot.$ext")
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
