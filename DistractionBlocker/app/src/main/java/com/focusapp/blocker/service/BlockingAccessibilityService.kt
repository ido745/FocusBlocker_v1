package com.focusapp.blocker.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import androidx.core.app.NotificationCompat
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.focusapp.blocker.data.AuthManager
import com.focusapp.blocker.data.AuthenticatedRepository
import com.focusapp.blocker.data.PreferencesManager
import com.focusapp.blocker.receiver.FocusDeviceAdminReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BlockingAccessibilityService : AccessibilityService() {

    companion object {
        /** Set by card buttons — guard is fully skipped so the user can change permissions. */
        @Volatile var openedFromApp = false
        /** Timestamp set by the top-right settings icon tap. Treated as active for 8 seconds.
         *  Using a timestamp instead of a boolean prevents it being cleared by MIUI's
         *  own-package transition event that fires when the activity goes to background. */
        @Volatile var openedViaSettingsIconAt = 0L
        private const val SETTINGS_ICON_WINDOW_MS = 8_000L

        /** Kept in sync by AuthViewModel. Used to pick a random motivation video when guard fires. */
        @Volatile var motivationVideos: List<String> = emptyList()
        @Volatile var motivationChannels: List<String> = emptyList()
        @Volatile var motivationDuration: Int = 10
    }

    private fun openedViaSettingsIcon(): Boolean =
        System.currentTimeMillis() - openedViaSettingsIconAt < SETTINGS_ICON_WINDOW_MS

    private val TAG = "BlockingService"
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var preferencesManager: PreferencesManager
    private lateinit var authManager: AuthManager
    private var repository: AuthenticatedRepository? = null

    private var blockedPackages = setOf<String>()
    private var blockedKeywords = setOf<String>()
    private var blockedWebsites = setOf<String>()
    private var whitelistedPackages = setOf<String>()
    private var whitelistedWebsites = setOf<String>()

    private var serverDeletionProtectionEnabled = false

    private val browserPackages = setOf(
        "com.android.chrome",
        "org.mozilla.firefox",
        "com.microsoft.emmx",
        "com.brave.browser",
        "com.opera.browser"
    )

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        preferencesManager = PreferencesManager(applicationContext)
        authManager = AuthManager(applicationContext)
        startStatusPolling()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.w(TAG, "🟢 SERVICE CONNECTED")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: "unknown"

        if (packageName == applicationContext.packageName) return

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        // If the user intentionally navigated to settings from our app, don't block them
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            shouldBlockSettingsAccess() &&
            !openedFromApp
        ) {
            checkAndBlockSettingsScreen()
        }

        if (isPackageWhitelisted(packageName)) return

        if (isPackageBlocked(packageName)) {
            blockApp("App is blocked")
            return
        }

        if (browserPackages.contains(packageName)) {
            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                checkForBlockedWebsites(rootNode)
                rootNode.recycle()
            }
        }

        if (blockedKeywords.isNotEmpty()) {
            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                checkForBlockedKeywords(rootNode)
                rootNode.recycle()
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun startStatusPolling() {
        serviceScope.launch {
            val serverUrl = preferencesManager.serverUrl.first()
            blockedPackages = preferencesManager.blockedPackages.first()
            blockedKeywords = preferencesManager.blockedKeywords.first()
            blockedWebsites = preferencesManager.blockedWebsites.first()

            repository = AuthenticatedRepository(applicationContext, serverUrl)

            while (true) {
                try {
                    val token = authManager.authToken.first()
                    if (!token.isNullOrBlank()) {
                        val result = repository?.getActiveSession()
                        result?.onSuccess { session ->
                            if (session != null) {
                                blockedPackages = session.blockedPackages.orEmpty().toSet()
                                blockedKeywords = session.blockedKeywords.orEmpty().toSet()
                                blockedWebsites = session.blockedWebsites.orEmpty().toSet()
                                whitelistedPackages = session.whitelistedPackages.orEmpty().toSet()
                                whitelistedWebsites = session.whitelistedWebsites.orEmpty().toSet()

                                val serverProtection = session.deletionProtectionEnabled ?: false
                                if (serverDeletionProtectionEnabled && !serverProtection) {
                                    deactivateDeviceAdmin()
                                }
                                serverDeletionProtectionEnabled = serverProtection
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Polling error: ${e.message}")
                }
                delay(3000)
            }
        }
    }

    private fun deactivateDeviceAdmin() {
        try {
            val dpm = applicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(applicationContext, FocusDeviceAdminReceiver::class.java)
            if (dpm.isAdminActive(adminComponent)) {
                dpm.removeActiveAdmin(adminComponent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deactivating admin", e)
        }
    }

    private fun checkForBlockedWebsites(node: AccessibilityNodeInfo) {
        val urlBarNode = findNodeById(node, "com.android.chrome:id/url_bar")
            ?: findNodeById(node, "org.mozilla.firefox:id/mozac_browser_toolbar_url_view")

        if (urlBarNode != null) {
            val url = urlBarNode.text?.toString()?.lowercase() ?: ""
            for (whitelistedSite in whitelistedWebsites) {
                if (url.contains(whitelistedSite.lowercase())) {
                    urlBarNode.recycle()
                    return
                }
            }
            for (blockedSite in blockedWebsites) {
                if (url.contains(blockedSite.lowercase())) {
                    blockApp("Website is blocked")
                    urlBarNode.recycle()
                    return
                }
            }
            urlBarNode.recycle()
        }
    }

    private fun checkForBlockedKeywords(node: AccessibilityNodeInfo) {
        if (scanNodeForKeywords(node)) {
            blockApp("Blocked content detected")
        }
    }

    private fun scanNodeForKeywords(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.lowercase() ?: ""
        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""

        for (keyword in blockedKeywords) {
            if (text.contains(keyword.lowercase()) || contentDesc.contains(keyword.lowercase())) {
                return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                if (scanNodeForKeywords(child)) {
                    child.recycle()
                    return true
                }
                child.recycle()
            }
        }
        return false
    }

    private fun findNodeById(node: AccessibilityNodeInfo, resourceId: String): AccessibilityNodeInfo? {
        if (node.viewIdResourceName == resourceId) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeById(child, resourceId)
            if (found != null) {
                child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }

    private fun isPackageBlocked(packageName: String): Boolean {
        val pkgLower = packageName.lowercase()
        return blockedPackages.any { it.lowercase() == pkgLower }
    }

    private fun isPackageWhitelisted(packageName: String): Boolean {
        val pkgLower = packageName.lowercase()
        return whitelistedPackages.any { it.lowercase() == pkgLower }
    }

    private fun shouldBlockSettingsAccess(): Boolean {
        if (!serverDeletionProtectionEnabled) return false
        val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(applicationContext.packageName)
    }

    private fun checkAndBlockSettingsScreen() {
        val rootNode = rootInActiveWindow ?: return
        val activePackage = rootNode.packageName?.toString() ?: ""

        if (!isSettingsPackage(activePackage)) {
            rootNode.recycle()
            return
        }

        val windowText = collectAllVisibleText(rootNode).lowercase()
        rootNode.recycle()

        val appLabel = applicationContext.packageManager
            .getApplicationLabel(applicationContext.applicationInfo).toString().lowercase()

        val mentionsOurApp = windowText.contains(appLabel) ||
            windowText.contains(applicationContext.packageName.lowercase())

        if (!mentionsOurApp) return

        val isAccessibilityList = windowText.contains("downloaded apps") || windowText.contains("installed services")
        val isServiceDetailPage = windowText.contains("use $appLabel") || windowText.contains("use focus blocker")
        val hasBatteryControl = windowText.contains("battery") || windowText.contains("optimization")
        val hasAppControls = windowText.contains("force stop") || windowText.contains("uninstall")
        val hasMiuiPermissionControl = windowText.contains("autostart") ||
            windowText.contains("auto start") ||
            windowText.contains("background popup") ||
            windowText.contains("display pop-up") ||
            windowText.contains("pop-up windows") ||
            windowText.contains("background start activity")

        if (isAccessibilityList || isServiceDetailPage || hasBatteryControl || hasAppControls || hasMiuiPermissionControl) {
            // Hold openedFromApp=true for the entire duration so stale Settings events
            // during transitions cannot re-trigger the guard.
            openedFromApp = true
            if (openedViaSettingsIcon()) {
                openedViaSettingsIconAt = 0L // consume
                Log.w(TAG, "🛡️ Settings opened via icon — pressing Back until leaving settings")
                pressBackUntilLeavingSettings(attemptsLeft = 6)
            } else {
                Log.w(TAG, "🛡️ Settings opened externally — blocking (package=$activePackage)")
                showLockedNotification()
                performGlobalAction(GLOBAL_ACTION_HOME)
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        val am = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                        am.killBackgroundProcesses(activePackage)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to kill $activePackage", e)
                    }
                    val allUrls = motivationVideos + motivationChannels
                    if (allUrls.isNotEmpty()) {
                        val videoUrl = allUrls.random()
                        FocusBlockerForegroundService.launchMotivation(applicationContext, videoUrl)
                    } else {
                        FocusBlockerForegroundService.launchMainActivity(applicationContext)
                    }
                    Handler(Looper.getMainLooper()).postDelayed({ openedFromApp = false }, 800)
                }, 300)
            }
        }
    }

    private fun isSettingsPackage(pkg: String) =
        pkg.contains("setting", ignoreCase = true) ||
        pkg.contains("securitycenter", ignoreCase = true) ||
        pkg.contains("systemmanager", ignoreCase = true) ||
        pkg.contains("permcenter", ignoreCase = true)

    private fun pressBackUntilLeavingSettings(attemptsLeft: Int) {
        if (attemptsLeft <= 0) {
            // Ran out of attempts — fall back to HOME
            performGlobalAction(GLOBAL_ACTION_HOME)
            Handler(Looper.getMainLooper()).postDelayed({ openedFromApp = false }, 800)
            return
        }
        performGlobalAction(GLOBAL_ACTION_BACK)
        Handler(Looper.getMainLooper()).postDelayed({
            val currentPkg = rootInActiveWindow?.packageName?.toString()
            when {
                // Window not yet assigned (mid-transition) — wait and check again
                currentPkg.isNullOrEmpty() -> pressBackUntilLeavingSettings(attemptsLeft - 1)
                // Still inside a settings app — keep pressing
                isSettingsPackage(currentPkg) -> pressBackUntilLeavingSettings(attemptsLeft - 1)
                // Left settings (home screen or our app) — release the lock
                else -> Handler(Looper.getMainLooper()).postDelayed({ openedFromApp = false }, 600)
            }
        }, 400)
    }

    private fun showLockedNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "focus_blocker_lock_channel"
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Focus Blocker Locks", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(com.focusapp.blocker.R.mipmap.ic_launcher)
            .setContentTitle("Settings Locked")
            .setContentText("Deletion protection is active.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        nm.notify(9001, notification)
    }

    private fun collectAllVisibleText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        node.text?.let { sb.append(it).append(' ') }
        node.contentDescription?.let { sb.append(it).append(' ') }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            sb.append(collectAllVisibleText(child))
            child.recycle()
        }
        return sb.toString()
    }

    private fun blockApp(reason: String) {
        Log.w(TAG, "🚫 BLOCKING: $reason")
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, "🚫 Focus Mode: $reason", Toast.LENGTH_SHORT).show()
        }
        performGlobalAction(GLOBAL_ACTION_HOME)
    }
}
