package com.focusapp.blocker.service

import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BlockingAccessibilityService : AccessibilityService() {

    private val TAG = "BlockingService"
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private lateinit var preferencesManager: PreferencesManager
    private lateinit var authManager: AuthManager
    private var repository: AuthenticatedRepository? = null

    // Blocking is always on; these lists come from the server config
    private var blockedPackages = setOf<String>()
    private var blockedKeywords = setOf<String>()
    private var blockedWebsites = setOf<String>()
    private var whitelistedPackages = setOf<String>()
    private var whitelistedWebsites = setOf<String>()

    // Tracks the server-side deletion protection state to detect when it turns off
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
        Log.w(TAG, "🟢 SERVICE CONNECTED - NOW MONITORING!")
        Log.w(TAG, "🟢 OUR APP PACKAGE NAME: ${applicationContext.packageName}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: "unknown"

        // CRITICAL: NEVER block our own app
        if (packageName == "com.focusapp.blocker" || packageName == applicationContext.packageName) {
            return
        }

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        if (isPackageWhitelisted(packageName)) {
            return
        }

        // Blocking is always active — no session toggle needed
        Log.d(TAG, "⚡ Event for: $packageName | Blocked=${blockedPackages.size} items")

        if (isPackageBlocked(packageName)) {
            Log.w(TAG, "🚫 BLOCKING APP: $packageName")
            blockApp("App is blocked")
            return
        }

        if (browserPackages.contains(packageName)) {
            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                val activePackage = rootNode.packageName?.toString()
                if (activePackage == "com.focusapp.blocker" || activePackage == applicationContext.packageName) {
                    rootNode.recycle()
                    return
                }
                checkForBlockedWebsites(rootNode)
                rootNode.recycle()
            }
        }

        if (blockedKeywords.isNotEmpty()) {
            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                val activePackage = rootNode.packageName?.toString()
                if (activePackage == "com.focusapp.blocker" || activePackage == applicationContext.packageName) {
                    rootNode.recycle()
                    return
                }
                checkForBlockedKeywords(rootNode)
                rootNode.recycle()
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
    }

    private fun startStatusPolling() {
        serviceScope.launch {
            val serverUrl = preferencesManager.serverUrl.first()
            // Fall back to local defaults while server is unreachable
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

                                // Detect when server-side deletion protection transitions to disabled
                                val serverProtection = session.deletionProtectionEnabled ?: false
                                if (serverDeletionProtectionEnabled && !serverProtection) {
                                    deactivateDeviceAdmin()
                                }
                                serverDeletionProtectionEnabled = serverProtection
                            }
                            Log.d(TAG, "✅ Always-on: Blocked=${blockedPackages.size} | Whitelisted=${whitelistedPackages.size}")
                        }?.onFailure { error ->
                            Log.e(TAG, "❌ Failed to fetch session: ${error.message}", error)
                        }
                    } else {
                        // Not authenticated — still block using local defaults
                        Log.d(TAG, "ℹ️ Not authenticated, using local blocklists")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error in status polling: ${e.message}", e)
                }

                delay(3000)
            }
        }
    }

    /**
     * Deactivates device admin when the 24-hour pending change for
     * "disable_deletion_protection" matures on the server.
     */
    private fun deactivateDeviceAdmin() {
        try {
            val dpm = applicationContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(applicationContext, FocusDeviceAdminReceiver::class.java)
            if (dpm.isAdminActive(adminComponent)) {
                dpm.removeActiveAdmin(adminComponent)
                Log.w(TAG, "✅ Device admin deactivated — deletion protection disabled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deactivating device admin", e)
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
                    Log.d(TAG, "🚫 Blocked website: $url")
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
        for (blocked in blockedPackages) {
            if (pkgLower == blocked.lowercase()) return true
        }
        return false
    }

    private fun isPackageWhitelisted(packageName: String): Boolean {
        val pkgLower = packageName.lowercase()
        for (whitelisted in whitelistedPackages) {
            if (pkgLower == whitelisted.lowercase()) return true
        }
        return false
    }

    private fun blockApp(reason: String) {
        Log.w(TAG, "🚫 BLOCKING: $reason")
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, "🚫 Focus Mode: $reason", Toast.LENGTH_SHORT).show()
        }
        performGlobalAction(GLOBAL_ACTION_HOME)
    }
}
