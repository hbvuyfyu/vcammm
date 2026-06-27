package com.vcam.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import java.io.File
import java.io.FileOutputStream

object MediaUtils {

    fun copyUriToFile(context: Context, uri: Uri, fileName: String): File? {
        return try {
            val destFile = File(context.filesDir, fileName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            destFile
        } catch (e: Exception) {
            null
        }
    }

    fun getImageFromUri(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun bitmapToYUV420(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val yuv = ByteArray(width * height * 3 / 2)
        val yIndex = 0
        val uvIndex = width * height
        var yPos = 0
        var uvPos = uvIndex

        for (j in 0 until height) {
            for (i in 0 until width) {
                val pixel = pixels[j * width + i]
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[yPos++] = y.toByte()

                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    yuv[uvPos++] = u.toByte()
                    yuv[uvPos++] = v.toByte()
                }
            }
        }
        return yuv
    }

    fun getMimeType(context: Context, uri: Uri): String? {
        return context.contentResolver.getType(uri)
    }

    fun isVideo(mimeType: String?): Boolean {
        return mimeType?.startsWith("video/") == true
    }

    fun getFilePath(context: Context, uri: Uri): String? {
        return try {
            val file = copyUriToFile(context, uri, "vcam_media_${System.currentTimeMillis()}")
            file?.absolutePath
        } catch (e: Exception) {
            null
        }
    }
}
