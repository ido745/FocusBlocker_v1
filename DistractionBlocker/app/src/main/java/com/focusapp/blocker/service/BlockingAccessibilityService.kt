package com.focusapp.blocker.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.focusapp.blocker.data.BlockerRepository
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
    private var repository: BlockerRepository? = null

    private var isSessionActive = false
    private var blockedPackages = setOf<String>()
    private var blockedKeywords = setOf<String>()
    private var blockedWebsites = setOf<String>()

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
        startStatusPolling()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Only process window state changes and content changes
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return
        }

        val packageName = event.packageName?.toString() ?: return

        // Ignore our own app
        if (packageName == applicationContext.packageName) {
            return
        }

        // Check if blocking is active
        if (!isSessionActive) {
            return
        }

        // 1. Check if the app itself is blocked
        if (blockedPackages.contains(packageName)) {
            Log.d(TAG, "Blocked app detected: $packageName")
            blockApp("App is blocked during focus session")
            return
        }

        // 2. Check if it's a browser and check for blocked websites
        if (browserPackages.contains(packageName)) {
            val rootNode = rootInActiveWindow ?: return
            checkForBlockedWebsites(rootNode)
            rootNode.recycle()
        }

        // 3. Check for blocked keywords in the content
        val rootNode = rootInActiveWindow ?: return
        checkForBlockedKeywords(rootNode)
        rootNode.recycle()
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

            repository = BlockerRepository(serverUrl)

            // Poll server status every 5 seconds
            while (true) {
                try {
                    val result = repository?.getStatus()
                    result?.onSuccess { state ->
                        isSessionActive = state.isSessionActive
                        blockedPackages = state.blockedPackages.toSet()
                        blockedKeywords = state.blockedKeywords.toSet()
                        blockedWebsites = state.blockedWebsites.toSet()

                        Log.d(TAG, "Status updated - Session active: $isSessionActive")
                    }?.onFailure { error ->
                        Log.e(TAG, "Failed to fetch status", error)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in status polling", e)
                }

                delay(5000) // Poll every 5 seconds
            }
        }
    }

    private fun checkForBlockedWebsites(node: AccessibilityNodeInfo) {
        // Look for URL bar in Chrome
        val urlBarNode = findNodeById(node, "com.android.chrome:id/url_bar")
            ?: findNodeById(node, "org.mozilla.firefox:id/mozac_browser_toolbar_url_view")

        if (urlBarNode != null) {
            val url = urlBarNode.text?.toString()?.lowercase() ?: ""

            for (blockedSite in blockedWebsites) {
                if (url.contains(blockedSite.lowercase())) {
                    Log.d(TAG, "Blocked website detected: $url")
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

    private fun blockApp(reason: String) {
        Log.d(TAG, "Blocking: $reason")
        performGlobalAction(GLOBAL_ACTION_HOME)
    }
}
