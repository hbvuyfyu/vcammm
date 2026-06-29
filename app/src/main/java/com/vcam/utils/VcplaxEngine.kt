package com.vcam.utils

import android.content.Context
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Random
import java.util.zip.ZipFile

/**
 * VcplaxEngine — manages the real camera injection engine.
 *
 * Architecture (reverse-engineered from working reference APK):
 *   1. Extract libvc.so, libshadowhook.so, vcplax (PIE executable) from the APK
 *   2. Copy them to /data/ (root-writable, ART-accessible)
 *   3. Run `vcplax <binderName> &` as root background process
 *      → vcplax registers itself as a Binder service in ServiceManager
 *      → vcplax loads libvc.so + libshadowhook.so
 *      → libvc.so uses ShadowHook to hook camera HAL functions in cameraserver
 *   4. Connect to the Binder service via ServiceManager.getService(binderName)
 *   5. Call start(filePath, autoRotate, loop) to begin injection
 *
 * This replaces Camera HAL1 LD_PRELOAD approach with proper ShadowHook-based
 * inline hooking that works on Android 10–14 with Camera2 API.
 */
object VcplaxEngine {

    private const val TAG = "VcplaxEngine"

    // Destination paths in root-writable area
    private const val VC_LIB_PATH     = "/data/libvc.so"
    private const val SHADOW_LIB_PATH = "/data/libvc++.so"
    private const val VCPLAX_PATH     = "/data/vcplax"

    // Prefs
    private const val PREFS_NAME = "vcam_engine_v2"
    private const val KEY_SVC    = "binder_svc_name"

    @Volatile private var binderName: String? = null
    @Volatile private var proxy: VcamBinderProxy? = null
    @Volatile private var initialized = false

    /**
     * Full setup: extract binaries from APK → copy to /data/ → launch vcplax.
     * Safe to call multiple times (kills existing instance first).
     */
    suspend fun setup(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            // 1. Detect architecture
            val is32bit = RootManager.runCommand("file /system/bin/cameraserver").output.contains("32-bit")
            val abiDir  = if (is32bit) "lib/armeabi-v7a" else "lib/arm64-v8a"
            Log.d(TAG, "ABI: $abiDir")

            // 2. Extract .so files from the APK zip to filesDir
            val filesDir = context.filesDir.also { it.mkdirs() }
            val apkPath  = context.applicationInfo.sourceDir
            ZipFile(apkPath).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.startsWith(abiDir) && it.name.endsWith(".so") }
                    .forEach { entry ->
                        val dest = File(filesDir, entry.name)
                        dest.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { ins ->
                            FileOutputStream(dest).use { out -> ins.copyTo(out) }
                        }
                        Log.d(TAG, "Extracted: ${entry.name}")
                    }
            }

            val libDir = "$filesDir/$abiDir"

            // 3. Kill any running vcplax
            RootManager.runCommand("killall vcplax 2>/dev/null; sleep 0.3")

            // 4. Copy to /data/ with root
            RootManager.runCommand("cp '$libDir/libvc.so' $VC_LIB_PATH && chmod 644 $VC_LIB_PATH")
            RootManager.runCommand("cp '$libDir/libshadowhook.so' $SHADOW_LIB_PATH && chmod 644 $SHADOW_LIB_PATH")
            RootManager.runCommand("cp '$libDir/vcplax.so' $VCPLAX_PATH && chmod 700 $VCPLAX_PATH")

            // Verify copy succeeded
            val vcplaxExists = RootManager.runCommand("test -f $VCPLAX_PATH && echo ok").output.contains("ok")
            if (!vcplaxExists) {
                Log.e(TAG, "vcplax binary not found after copy")
                return@withContext false
            }

            // 5. Get or generate a unique Binder service name
            val svcName = getOrCreateServiceName(context)
            binderName  = svcName

            // 6. Temporarily disable SELinux, start vcplax, re-enable
            RootManager.runCommand("setenforce 0 2>/dev/null || true")
            RootManager.runCommand("$VCPLAX_PATH $svcName &")

            Log.d(TAG, "vcplax started, binder name: $svcName")
            initialized = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "setup failed: ${e.message}", e)
            false
        }
    }

    /**
     * Connect to the running vcplax Binder service.
     * Retries for up to 5 seconds.
     */
    suspend fun connect(): VcamBinderProxy? = withContext(Dispatchers.IO) {
        val name = binderName ?: return@withContext null

        repeat(10) { attempt ->
            try {
                val iBinder = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java)
                    .invoke(null, name) as? IBinder

                if (iBinder != null) {
                    val p = VcamBinderProxy(iBinder)
                    proxy = p
                    Log.d(TAG, "Connected to vcplax Binder (attempt ${attempt + 1})")
                    return@withContext p
                }
            } catch (e: Exception) {
                Log.w(TAG, "connect attempt ${attempt + 1} failed: ${e.message}")
            }
            delay(500)
        }
        Log.e(TAG, "Could not connect to vcplax Binder service")
        null
    }

    /**
     * Start camera injection with the given media file.
     * @param mediaPath  absolute path to the image or video file
     * @param loop       loop video indefinitely (ignored for images)
     */
    suspend fun startInjection(mediaPath: String, loop: Boolean = true): Boolean =
        withContext(Dispatchers.IO) {
            val svc = proxy ?: connect() ?: return@withContext false
            return@withContext try {
                val result = svc.start(mediaPath, autoRotate = false, loop = loop)
                Log.d(TAG, "start() returned: $result")
                // Wait for the injection to become active
                var retries = 0
                while (retries < 6 && !svc.isRunning) {
                    delay(500); retries++
                }
                val running = svc.isRunning
                Log.d(TAG, "Injection active: $running")
                // Re-enable SELinux after successful start
                if (running) RootManager.runCommand("setenforce 1 2>/dev/null || true")
                running
            } catch (e: Exception) {
                Log.e(TAG, "startInjection failed: ${e.message}", e)
                false
            }
        }

    /** Stop injection and restore normal camera behaviour. */
    fun stopInjection() {
        try { proxy?.stop() } catch (_: Exception) {}
        RootManager.runCommand("killall vcplax 2>/dev/null || true")
        RootManager.runCommand("setenforce 1 2>/dev/null || true")
        proxy       = null
        initialized = false
    }

    fun setRotation(degrees: Int) {
        try { proxy?.setRotation(degrees) } catch (_: Exception) {}
    }

    fun setMirror(enabled: Boolean) {
        try { proxy?.setMirror(enabled) } catch (_: Exception) {}
    }

    val isRunning: Boolean get() = proxy?.isRunning == true

    fun getProxy(): VcamBinderProxy? = proxy

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun getOrCreateServiceName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var name  = prefs.getString(KEY_SVC, null)
        if (!name.isNullOrEmpty()) return name

        // Blend in with existing system services
        name = try {
            val services = Class.forName("android.os.ServiceManager")
                .getMethod("listServices")
                .invoke(null) as? Array<*>
            if (!services.isNullOrEmpty()) {
                val base = services[Random().nextInt(services.size)] as? String ?: ""
                base + randomString(1, 3)
            } else {
                randomString(6, 12)
            }
        } catch (_: Exception) {
            randomString(6, 12)
        }

        prefs.edit().putString(KEY_SVC, name).apply()
        return name
    }

    private fun randomString(min: Int, max: Int): String {
        val len = Random().nextInt(max - min + 1) + min
        return (1..len).map { ('a'..'z').random() }.joinToString("")
    }
}
