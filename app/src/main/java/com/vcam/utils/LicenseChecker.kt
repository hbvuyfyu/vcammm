package com.vcam.utils

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object LicenseChecker {

    private const val CODES_URL = "https://raw.githubusercontent.com/hbvuyfyu/vcammm/main/allcod"
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
        try {
            val url = URL(CODES_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Cache-Control", "no-cache, no-store")

            val responseCode = conn.responseCode
            if (responseCode != 200) return@withContext VerifyResult.NETWORK_ERROR

            val content = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            conn.disconnect()

            // Ignore blank lines and comment lines (starting with #)
            val codes = content.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }

            when {
                codes.isEmpty() -> VerifyResult.SERVER_EMPTY
                codes.contains(code.trim()) -> VerifyResult.VALID
                else -> VerifyResult.INVALID
            }
        } catch (e: Exception) {
            VerifyResult.NETWORK_ERROR
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
