package com.vcam.utils

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object LicenseChecker {

    // Multiple mirrors — if GitHub raw is blocked, jsDelivr CDN usually works
    private val CODES_URLS = listOf(
        "https://cdn.jsdelivr.net/gh/hbvuyfyu/vcammm@main/allcod",
        "https://raw.githubusercontent.com/hbvuyfyu/vcammm/main/allcod",
        "https://ghproxy.com/https://raw.githubusercontent.com/hbvuyfyu/vcammm/main/allcod"
    )

    private const val PREFS_NAME = "vcam_license"
    private const val KEY_CODE = "activated_code"

    fun getSavedCode(context: Context): String? {
        return prefs(context).getString(KEY_CODE, null)
    }

    fun saveCode(context: Context, code: String) {
        prefs(context).edit().putString(KEY_CODE, code.trim()).apply()
    }

    fun clearCode(context: Context) {
        prefs(context).edit().remove(KEY_CODE).apply()
    }

    suspend fun verifyCode(code: String): VerifyResult = withContext(Dispatchers.IO) {
        for (urlStr in CODES_URLS) {
            try {
                val content = fetchUrl(urlStr) ?: continue

                val codes = content.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }

                return@withContext when {
                    codes.isEmpty() -> VerifyResult.SERVER_EMPTY
                    codes.contains(code.trim()) -> VerifyResult.VALID
                    else -> VerifyResult.INVALID
                }
            } catch (_: Exception) {
                // Try next mirror
            }
        }
        // All mirrors failed
        VerifyResult.NETWORK_ERROR
    }

    private fun fetchUrl(urlStr: String): String? {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.requestMethod = "GET"
        conn.setRequestProperty("Cache-Control", "no-cache, no-store")
        conn.setRequestProperty("User-Agent", "VirtualCam/1.0")
        return if (conn.responseCode == 200) {
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                .also { conn.disconnect() }
        } else {
            conn.disconnect()
            null
        }
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    enum class VerifyResult {
        VALID,
        INVALID,
        SERVER_EMPTY,
        NETWORK_ERROR
    }
}
