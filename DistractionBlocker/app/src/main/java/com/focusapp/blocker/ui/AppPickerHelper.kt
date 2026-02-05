package com.focusapp.blocker.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable?
)

class AppPickerHelper(private val context: Context) {

    suspend fun getInstalledApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager

        // Query for ALL apps that have a launcher icon (includes all user apps!)
        val intent = Intent(Intent.ACTION_MAIN, null)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = packageManager.queryIntentActivities(intent, 0)

        // Convert to AppInfo and deduplicate by package name
        val uniqueApps = mutableMapOf<String, AppInfo>()

        for (resolveInfo in activities) {
            try {
                val packageName = resolveInfo.activityInfo.packageName
                val appInfo = packageManager.getApplicationInfo(packageName, 0)

                // Skip if we already added this package
                if (uniqueApps.containsKey(packageName)) continue

                val appName = appInfo.loadLabel(packageManager).toString()
                val icon = try {
                    appInfo.loadIcon(packageManager)
                } catch (e: Exception) {
                    null
                }

                uniqueApps[packageName] = AppInfo(
                    packageName = packageName,
                    appName = appName,
                    icon = icon
                )
            } catch (e: Exception) {
                // Skip apps that can't be loaded
                continue
            }
        }

        uniqueApps.values.sortedBy { it.appName.lowercase() }
    }

    suspend fun searchApps(query: String): List<AppInfo> = withContext(Dispatchers.IO) {
        if (query.isBlank()) {
            getInstalledApps()
        } else {
            getInstalledApps().filter { app ->
                app.appName.contains(query, ignoreCase = true) ||
                app.packageName.contains(query, ignoreCase = true)
            }
        }
    }
}
