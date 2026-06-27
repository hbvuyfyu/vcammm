package com.vcam.utils

  import android.content.Context
  import android.content.pm.ApplicationInfo
  import android.content.pm.PackageManager
  import com.vcam.model.AppInfo

  object AppLoader {

      fun getInstalledApps(context: Context): List<AppInfo> {
          val pm = context.packageManager
          val packages = try {
              pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
          } catch (e: Exception) {
              pm.getInstalledPackages(0)
          }

          return packages
              .filter { pkg -> pkg.packageName != context.packageName }
              .mapNotNull { pkg ->
                  val appInfo = pkg.applicationInfo ?: return@mapNotNull null
                  val label = try { appInfo.loadLabel(pm).toString() } catch (_: Exception) { pkg.packageName }
                  val icon = try { appInfo.loadIcon(pm) } catch (_: Exception) { pm.defaultActivityIcon }
                  val useCamera = pkg.requestedPermissions?.any { it == "android.permission.CAMERA" } ?: false
                  val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                  AppInfo(
                      appName = label,
                      packageName = pkg.packageName,
                      icon = icon,
                      useCamera = useCamera,
                      isSystem = isSystem
                  )
              }
              .sortedWith(compareByDescending<AppInfo> { it.useCamera }.thenBy { it.appName })
      }

      fun getCameraApps(context: Context) = getInstalledApps(context).filter { it.useCamera }
      fun getUserApps(context: Context) = getInstalledApps(context).filter { !it.isSystem }
  }
  