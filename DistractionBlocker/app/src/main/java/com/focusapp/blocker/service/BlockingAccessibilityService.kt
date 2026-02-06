package com.focusapp.blocker.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.focusapp.blocker.data.AuthManager
import com.focusapp.blocker.data.AuthenticatedRepository
import com.focusapp.blocker.data.PreferencesManager
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

    private var isSessionActive = false
    private var blockedPackages = setOf<String>()
    private var blockedKeywords = setOf<String>()
    private var blockedWebsites = setOf<String>()
    private var whitelistedPackages = setOf<String>()
    private var whitelistedWebsites = setOf<String>()

    // Browser package names
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
        Log.w(TAG, "🟢 ========================================")
        Log.w(TAG, "🟢 SERVICE CONNECTED - NOW MONITORING!")
        Log.w(TAG, "🟢 OUR APP PACKAGE NAME: ${applicationContext.packageName}")
        Log.w(TAG, "🟢 ========================================")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: "unknown"

        // 🚨 CRITICAL: NEVER EVER block our own app - check this FIRST before anything else
        if (packageName == "com.focusapp.blocker" || packageName == applicationContext.packageName) {
            Log.d(TAG, "🛡️ Self-check: Ignoring our own app ($packageName)")
            return  // Exit immediately
        }

        // Log ALL events for debugging
        Log.d(TAG, "📱 RAW EVENT: type=${event.eventType} package=$packageName")

        // Only process window state changes and content changes
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return
        }

        // Check if this app is whitelisted (never block whitelisted apps)
        if (isPackageWhitelisted(packageName)) {
            Log.d(TAG, "✅ Whitelisted app: $packageName - allowing")
            return
        }

        Log.w(TAG, "⚡ Event received for package: $packageName (Session active: $isSessionActive)")

        // Check if blocking is active
        if (!isSessionActive) {
            return
        }

        // 1. Check if the app itself is blocked (exact match or partial match)
        if (isPackageBlocked(packageName)) {
            Log.w(TAG, "🚫 BLOCKING APP: $packageName")
            blockApp("App is blocked during focus session")
            return
        }

        // 2. Check if it's a browser and check for blocked websites
        if (browserPackages.contains(packageName)) {
            Log.d(TAG, "Browser detected: $packageName - checking for blocked websites")
            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                // 🚨 CRITICAL: Check if the active window is our own app before scanning
                val activePackage = rootNode.packageName?.toString()
                if (activePackage == "com.focusapp.blocker" || activePackage == applicationContext.packageName) {
                    Log.d(TAG, "🛡️ Active window is our app - skipping website check")
                    rootNode.recycle()
                    return
                }
                checkForBlockedWebsites(rootNode)
                rootNode.recycle()
            }
        }

        // 3. Check for blocked keywords in the content
        if (blockedKeywords.isNotEmpty()) {
            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                // 🚨 CRITICAL: Check if the active window is our own app before scanning
                val activePackage = rootNode.packageName?.toString()
                if (activePackage == "com.focusapp.blocker" || activePackage == applicationContext.packageName) {
                    Log.d(TAG, "🛡️ Active window is our app - skipping keyword check")
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
            // Load preferences first
            val serverUrl = preferencesManager.serverUrl.first()
            blockedPackages = preferencesManager.blockedPackages.first()
            blockedKeywords = preferencesManager.blockedKeywords.first()
            blockedWebsites = preferencesManager.blockedWebsites.first()

            Log.d(TAG, "📡 Initializing with server URL: $serverUrl")
            Log.d(TAG, "Initial blocked packages: ${blockedPackages.size}")
            Log.d(TAG, "Initial blocked keywords: ${blockedKeywords.size}")
            Log.d(TAG, "Initial blocked websites: ${blockedWebsites.size}")

            repository = AuthenticatedRepository(applicationContext, serverUrl)

            // Poll server status every 3 seconds
            while (true) {
                try {
                    val token = authManager.authToken.first()
                    if (!token.isNullOrBlank()) {
                        // Authenticated: use the multi-device session endpoint
                        val result = repository?.getActiveSession()
                        result?.onSuccess { session ->
                            val wasActive = isSessionActive
                            isSessionActive = session?.isActive == true

                            if (session != null) {
                                blockedPackages = session.blockedPackages.orEmpty().toSet()
                                blockedKeywords = session.blockedKeywords.orEmpty().toSet()
                                blockedWebsites = session.blockedWebsites.orEmpty().toSet()
                                whitelistedPackages = session.whitelistedPackages.orEmpty().toSet()
                                whitelistedWebsites = session.whitelistedWebsites.orEmpty().toSet()
                            } else {
                                isSessionActive = false
                            }

                            if (wasActive != isSessionActive) {
                                Log.w(TAG, "🔄 Session state changed: ${if (isSessionActive) "ACTIVE" else "INACTIVE"}")
                            }

                            Log.d(TAG, "✅ Status: Active=$isSessionActive | Blocked=${blockedPackages.size} | Whitelisted=${whitelistedPackages.size}")
                        }?.onFailure { error ->
                            Log.e(TAG, "❌ Failed to fetch session: ${error.message}", error)
                        }
                    } else {
                        // Not authenticated - no blocking
                        isSessionActive = false
                        Log.d(TAG, "⏸️ Not authenticated, blocking inactive")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error in status polling: ${e.message}", e)
                }

                delay(3000) // Poll every 3 seconds
            }
        }
    }

    private fun checkForBlockedWebsites(node: AccessibilityNodeInfo) {
        // Look for URL bar in Chrome
        val urlBarNode = findNodeById(node, "com.android.chrome:id/url_bar")
            ?: findNodeById(node, "org.mozilla.firefox:id/mozac_browser_toolbar_url_view")

        if (urlBarNode != null) {
            val url = urlBarNode.text?.toString()?.lowercase() ?: ""

            // Check whitelist first - never block whitelisted sites
            for (whitelistedSite in whitelistedWebsites) {
                if (url.contains(whitelistedSite.lowercase())) {
                    Log.d(TAG, "✅ Whitelisted website: $url - allowing")
                    urlBarNode.recycle()
                    return
                }
            }

            // Check blocked sites
            for (blockedSite in blockedWebsites) {
                if (url.contains(blockedSite.lowercase())) {
                    Log.d(TAG, "🚫 Blocked website detected: $url")
                    blockApp("Website is blocked during focus session")
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
        // Check text content
        val text = node.text?.toString()?.lowercase() ?: ""
        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""

        for (keyword in blockedKeywords) {
            if (text.contains(keyword.lowercase()) || contentDesc.contains(keyword.lowercase())) {
                Log.d(TAG, "Blocked keyword detected: $keyword")
                return true
            }
        }

        // Recursively check children
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
        if (node.viewIdResourceName == resourceId) {
            return node
        }

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

        Log.d(TAG, "🔍 Checking if blocked: $pkgLower against ${blockedPackages.size} blocked packages: $blockedPackages")

        for (blocked in blockedPackages) {
            val blockedLower = blocked.lowercase()

            // Exact match only
            if (pkgLower == blockedLower) {
                Log.d(TAG, "✓ Exact match: $pkgLower == $blockedLower")
                return true
            }
        }

        Log.d(TAG, "✗ Not blocked: $pkgLower")
        return false
    }

    private fun isPackageWhitelisted(packageName: String): Boolean {
        val pkgLower = packageName.lowercase()

        for (whitelisted in whitelistedPackages) {
            val whitelistedLower = whitelisted.lowercase()

            // Exact match only
            if (pkgLower == whitelistedLower) {
                return true
            }
        }

        return false
    }

    private fun blockApp(reason: String) {
        Log.w(TAG, "🚫 BLOCKING: $reason")

        // Show toast notification
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                applicationContext,
                "🚫 Focus Mode: $reason",
                Toast.LENGTH_SHORT
            ).show()
        }

        // Return to home screen
        performGlobalAction(GLOBAL_ACTION_HOME)
    }
}
