package com.vcam.utils

  import android.util.Log
  import com.topjohnwu.superuser.Shell
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.withContext
  import java.io.File

  object RootManager {

      private const val TAG = "RootManager"

      init {
          Shell.enableVerboseLogging = false
          Shell.setDefaultBuilder(
              Shell.Builder.create()
                  .setFlags(Shell.FLAG_REDIRECT_STDERR)
                  .setTimeout(15)
          )
      }

      /** Try every available root method; returns true if ANY succeeds */
      suspend fun requestRoot(): Boolean = withContext(Dispatchers.IO) {
          // Method 1: libsu Shell.getShell()
          try {
              val shell = Shell.getShell()
              if (shell.isRoot) {
                  Log.d(TAG, "Root OK via libsu Shell.getShell()")
                  return@withContext true
              }
          } catch (e: Exception) {
              Log.w(TAG, "libsu getShell failed: ${e.message}")
          }

          // Method 2: Shell.cmd("id")
          try {
              val result = Shell.cmd("id").exec()
              val out = result.out.joinToString(" ")
              if (out.contains("uid=0")) {
                  Log.d(TAG, "Root OK via Shell.cmd(id)")
                  return@withContext true
              }
          } catch (e: Exception) {
              Log.w(TAG, "Shell.cmd id failed: ${e.message}")
          }

          // Method 3: Runtime.exec su -c id
          try {
              val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
              val out = proc.inputStream.bufferedReader().readText()
              proc.waitFor()
              if (out.contains("uid=0")) {
                  Log.d(TAG, "Root OK via Runtime su -c id")
                  return@withContext true
              }
          } catch (e: Exception) {
              Log.w(TAG, "Runtime su failed: ${e.message}")
          }

          // Method 4: Check su binary presence
          val suPaths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su",
                               "/vendor/bin/su", "/su/bin/su", "/magisk/.core/bin/su")
          val hasSu = suPaths.any { File(it).exists() }
          if (hasSu) {
              Log.d(TAG, "Root OK via su binary found")
              return@withContext true
          }

          Log.e(TAG, "Root NOT available — all methods failed")
          false
      }

      fun isRooted(): Boolean {
          // Use Shell.isAppGrantedRoot() — may be null if not yet determined
          val granted = Shell.isAppGrantedRoot()
          if (granted == true) return true
          // Fallback: try a quick shell command
          return try {
              val result = Shell.cmd("id").exec()
              result.out.joinToString(" ").contains("uid=0")
          } catch (e: Exception) {
              false
          }
      }

      fun runCommand(command: String): ShellResult {
          return try {
              val result = Shell.cmd(command).exec()
              ShellResult(
                  success = result.isSuccess,
                  output = result.out.joinToString("\n"),
                  error = result.err.joinToString("\n")
              )
          } catch (e: Exception) {
              ShellResult(success = false, output = "", error = e.message ?: "Unknown error")
          }
      }

      fun runCommands(vararg commands: String): Boolean {
          return try {
              val result = Shell.cmd(*commands).exec()
              result.isSuccess
          } catch (e: Exception) {
              false
          }
      }

      suspend fun runCommandAsync(command: String): ShellResult = withContext(Dispatchers.IO) {
          runCommand(command)
      }

      fun checkV4L2Loopback(): Boolean {
          val result = runCommand("ls /dev/video* 2>/dev/null")
          return result.success && result.output.isNotBlank()
      }

      fun getVideoDevices(): List<String> {
          val result = runCommand("ls /dev/video* 2>/dev/null")
          return if (result.success && result.output.isNotBlank()) {
              result.output.trim().split("\n").filter { it.isNotBlank() }
          } else {
              emptyList()
          }
      }

      fun setSystemProp(key: String, value: String): Boolean {
          val result = runCommand("setprop $key $value")
          return result.success
      }

      fun grantPermission(packageName: String, permission: String): Boolean {
          runCommand("pm grant $packageName $permission 2>/dev/null || appops set $packageName CAMERA allow")
          return true
      }

      fun killApp(packageName: String) {
          runCommand("am force-stop $packageName")
      }

      data class ShellResult(
          val success: Boolean,
          val output: String,
          val error: String
      )
  }
  