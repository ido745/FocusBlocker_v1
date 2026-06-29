package com.focusapp.blocker.service

import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.Toast
import com.focusapp.blocker.data.AdultBlockList
import com.focusapp.blocker.data.PreferencesManager
import com.focusapp.blocker.receiver.FocusDeviceAdminReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BlockingAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var openedFromApp = false
        @Volatile var openedViaSettingsIconAt = 0L
        private const val SETTINGS_ICON_WINDOW_MS = 8_000L

        @Volatile var motivationVideos: List<String> = emptyList()
        @Volatile var motivationChannels: List<String> = emptyList()
        @Volatile var motivationGalleryVideos: List<String> = emptyList()
        @Volatile var motivationPhrases: List<String> = emptyList()
        @Volatile var motivationDuration: Int = 10

        @Volatile var motivationActive = false
        // Set the moment we decide to launch motivation, cleared when the player dismisses.
        // Allows the guard to re-launch the video even before the composable becomes visible.
        @Volatile var lastMotivationUrl = ""
        // True while settings protection is active — checked by the poll runnable so the overlay
        // stays visible even before lastMotivationUrl is set (which happens ~300 ms after trigger).
        @Volatile var settingsProtectionArmed = false
        // Epoch ms when motivation was last launched — used to decide if the timer has elapsed.
        @Volatile var motivationStartedAt = 0L

        // Populated by AuthViewModel at startup; falls back to hardcoded list until download completes
        @Volatile var adultBlockingLevel = 0  // 0=off, 1=sites only, 2=sites+keywords
        @Volatile var adultDomains: Set<String> = AdultBlockList.FALLBACK_DOMAINS

        @Volatile var settingsProtectionLevel = 0  // 0=off, 1=low, 2=high
        @Volatile var motivationOnBlock = false
        @Volatile var motivationOnSettings = false
        @Volatile var blockYoutubeShorts = false
        @Volatile var blockInstagramReels = false
        @Volatile var whitelistedKeywords: Set<String> = emptySet()
    }

    private fun openedViaSettingsIcon(): Boolean =
        System.currentTimeMillis() - openedViaSettingsIconAt < SETTINGS_ICON_WINDOW_MS

    private val TAG = "BlockingService"
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    // Resolved once from PackageManager so label changes (locale, etc.) are caught at runtime.
    private val ownAppLabel: String by lazy {
        try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(packageName, 0)
            ).toString()
        } catch (_: Exception) { "Focus Blocker" }
    }

    // Full-screen opaque overlay drawn via WindowManager above all other windows.
    // Shown the instant settings protection triggers — covers both split-screen panes and
    // absorbs all touches so the user cannot interact with Settings underneath.
    private var overlayView: FrameLayout? = null
    private var overlayCountdownEndMs = 0L
    private var lastBlockReason = ""
    // Set when the user manually taps "Continue" on the overlay — prevents the poll loop
    // from re-showing the overlay after the user has already dismissed it.
    private var overlayManuallyClosed = false
    // True when the overlay is protecting against a blocked website (vs. settings protection).
    // Controls which "danger zone" check the poll uses when deciding to press Back.
    private var isWebsiteProtection = false

    private val protectionPollHandler = Handler(Looper.getMainLooper())
    private val protectionPollRunnable = object : Runnable {
        override fun run() {
            if (!settingsProtectionArmed) {
                // Protection ended (motivation timer elapsed or was not triggered) — clean up.
                hideBlockingOverlay()
                isWebsiteProtection = false
                return
            }
            val countdownDone = System.currentTimeMillis() >= overlayCountdownEndMs
            if (countdownDone && (motivationActive || overlayManuallyClosed)) {
                // Countdown finished and user dismissed or motivation is now playing.
                // Remove the overlay so the video (or home screen) is visible.
                hideBlockingOverlay()
                protectionPollHandler.postDelayed(this, 200)
                return
            }
            // Keep overlay visible — either countdown still running or waiting for motivation.
            showBlockingOverlay(lastBlockReason)
            // Back out of the danger zone (settings page or blocked website).
            if (isWebsiteProtection) tryDismissBrowserPage() else tryDismissSettingsWindows()
            if (countdownDone) {
                // Countdown done but motivation not yet visible — keep pushing it to front.
                val url = lastMotivationUrl
                if (url.isNotEmpty()) {
                    FocusBlockerForegroundService.launchMotivation(applicationContext, url)
                }
            }
            protectionPollHandler.postDelayed(this, if (countdownDone) 150 else 200)
        }
    }

    private lateinit var preferencesManager: PreferencesManager

    private var blockedPackages = setOf<String>()
    private var blockedKeywords = setOf<String>()
    private var blockedWebsites = setOf<String>()
    private var whitelistedPackages = setOf<String>()
    private var whitelistedWebsites = setOf<String>()
    private var deletionProtectionEnabled = false

    private val browserPackages = setOf(
        "com.android.chrome",
        "org.mozilla.firefox",
        "com.microsoft.emmx",
        "com.brave.browser",
        "com.opera.browser",
        "com.sec.android.app.sbrowser"   // Samsung Internet
    )

    override fun onCreate() {
        super.onCreate()
        preferencesManager = PreferencesManager(applicationContext)
        startDataStoreCollection()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.w(TAG, "🟢 SERVICE CONNECTED")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: "unknown"

        if (packageName == applicationContext.packageName) return

        // Reset the "opened from app" gate when the user navigates away from settings
        if (openedFromApp &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            !isSettingsPackage(packageName)) {
            openedFromApp = false
        }

        if ((motivationActive || lastMotivationUrl.isNotEmpty()) &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val url = lastMotivationUrl
            if (url.isNotEmpty()) {
                FocusBlockerForegroundService.launchMotivation(applicationContext, url)
            } else {
                FocusBlockerForegroundService.launchMainActivity(applicationContext)
            }
            return
        }

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        if (!openedFromApp && settingsProtectionLevel > 0 && isSettingsPackage(packageName)) {
            // Skip throttle for window-state changes — they fire once per navigation,
            // so we must react immediately rather than waiting for the next content event.
            checkAndBlockSettingsScreen(
                skipThrottle = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            )
        }

        // Never keyword-scan settings pages — the app's own description triggers false positives
        if (isSettingsPackage(packageName)) return

        if (isPackageWhitelisted(packageName)) return

        if (isPackageBlocked(packageName)) {
            blockApp("${getAppLabel(packageName)} is blocked", packageName)
            return
        }

        // YouTube Shorts / Instagram Reels in-app detection (has its own per-feature throttle)
        if (blockYoutubeShorts && packageName == "com.google.android.youtube") {
            if (checkForYoutubeShorts(event)) return
        }
        if (blockInstagramReels &&
            (packageName == "com.instagram.android" || packageName == "com.instagram.lite")) {
            if (checkForInstagramReels(event)) return
        }

        // Throttle website/keyword scans on content-change events — reduces CPU usage without
        // affecting Shorts/Reels detection (already handled above with their own throttles).
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            val now = System.currentTimeMillis()
            if (now - lastGlobalContentScanMs < 300) return
            lastGlobalContentScanMs = now
        }

        // Skip rootInActiveWindow (expensive) when nothing needs to be scanned for this package.
        val needsWebsiteScan = browserPackages.contains(packageName)
        val needsKeywordScan = blockedKeywords.isNotEmpty() || adultBlockingLevel >= 2
        if (!needsWebsiteScan && !needsKeywordScan) return

        // Get the active window and verify it belongs neither to us nor to settings.
        // Events from the keyboard or other overlays arrive with a different packageName than the
        // visible app — so we must whitelist-check the ROOT's package, not just the event's.
        val scanRoot = rootInActiveWindow ?: return
        val scanPkg = scanRoot.packageName?.toString() ?: ""
        if (scanPkg == applicationContext.packageName || isSettingsPackage(scanPkg)) {
            scanRoot.recycle()
            return
        }
        if (isPackageWhitelisted(scanPkg)) {
            scanRoot.recycle()
            return
        }

        val siteWhitelisted = if (needsWebsiteScan) {
            checkForBlockedWebsites(scanRoot)
        } else false

        if (!siteWhitelisted && needsKeywordScan) {
            checkForBlockedKeywords(scanRoot)
        }
        scanRoot.recycle()
    }

    override fun onInterrupt() {}

    // Called the moment the user disables this accessibility service in Settings.
    // If this app is the Device Owner, write the service back into ENABLED_ACCESSIBILITY_SERVICES
    // immediately — the system will re-bind us within milliseconds.
    override fun onUnbind(intent: Intent?): Boolean {
        Log.w(TAG, "🔴 Accessibility service unbound — attempting self-heal")
        Handler(Looper.getMainLooper()).postDelayed({ reenableAccessibilityService() }, 100)
        return true
    }

    private fun reenableAccessibilityService() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (!dpm.isDeviceOwnerApp(packageName)) return
            val admin = ComponentName(this, FocusDeviceAdminReceiver::class.java)
            val serviceFlat = ComponentName(this, BlockingAccessibilityService::class.java).flattenToString()
            val current = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val services = current.split(":").filter { it.isNotBlank() }.toMutableSet()
            if (services.add(serviceFlat)) {
                dpm.setSecureSetting(admin, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services.joinToString(":"))
                dpm.setSecureSetting(admin, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
                Log.w(TAG, "✅ Accessibility service re-enabled via Device Owner")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Self-heal failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        mainHandler.removeCallbacks(deferredShortsCheck)
        mainHandler.removeCallbacks(deferredReelsCheck)
        protectionPollHandler.removeCallbacks(protectionPollRunnable)
        hideBlockingOverlay()
    }

    private fun startDataStoreCollection() {
        serviceScope.launch { preferencesManager.blockedPackages.collect { blockedPackages = it } }
        serviceScope.launch { preferencesManager.blockedKeywords.collect { blockedKeywords = it } }
        serviceScope.launch { preferencesManager.blockedWebsites.collect { blockedWebsites = it } }
        serviceScope.launch { preferencesManager.whitelistedPackages.collect { whitelistedPackages = it } }
        serviceScope.launch { preferencesManager.whitelistedWebsites.collect { whitelistedWebsites = it } }
        serviceScope.launch { preferencesManager.whitelistedKeywords.collect { BlockingAccessibilityService.whitelistedKeywords = it } }
        serviceScope.launch {
            preferencesManager.deletionProtection.collect { enabled ->
                val wasEnabled = deletionProtectionEnabled
                deletionProtectionEnabled = enabled
                if (wasEnabled && !enabled) deactivateDeviceAdmin()
            }
        }
        // Single collector for all motivation data — reads from the JSON config so gallery
        // videos, phrases and duration are included (legacy individual keys may be empty).
        serviceScope.launch {
            preferencesManager.motivationConfigFlow.collect { config ->
                motivationVideos = config.videos.map { it.url }
                motivationChannels = config.channels.map { it.url }
                motivationGalleryVideos = config.galleryVideos.map { it.url }
                motivationPhrases = config.phrases
                motivationDuration = config.duration
            }
        }
        serviceScope.launch { preferencesManager.settingsProtectionLevel.collect { settingsProtectionLevel = it } }
        serviceScope.launch { preferencesManager.motivationOnBlock.collect { motivationOnBlock = it } }
        serviceScope.launch { preferencesManager.motivationOnSettings.collect { motivationOnSettings = it } }
        serviceScope.launch { preferencesManager.blockYoutubeShorts.collect { blockYoutubeShorts = it } }
        serviceScope.launch { preferencesManager.blockInstagramReels.collect { blockInstagramReels = it } }
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

    private var lastShortsCheckMs = 0L
    private var lastReelsCheckMs = 0L
    private var lastReelsBlockMs = 0L       // debounce: prevents immediate+deferred double-block
    private var lastGlobalContentScanMs = 0L // throttle for website/keyword scans

    // Cached browser URL — retained when the URL bar hides during SPA navigation (e.g. YouTube Shorts).
    private var lastKnownBrowserUrl: String = ""
    private var lastBrowserPackage: String = ""

    // Deferred runnables: fire 1 s after a YouTube / Instagram window-state change to catch
    // Shorts / Reels opened from the home feed, where the class name event fires too early
    // (before the player UI is rendered) and content-change events may stop before we check.
    private val deferredShortsCheck = Runnable {
        if (!blockYoutubeShorts) return@Runnable
        val root = rootInActiveWindow ?: return@Runnable
        try {
            if (root.packageName?.toString() != "com.google.android.youtube") return@Runnable
            if (isShortsPlayerVisible(root)) {
                Toast.makeText(applicationContext, "🚫 Focus Mode: YouTube Shorts blocked", Toast.LENGTH_SHORT).show()
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        } finally { root.recycle() }
    }

    private val deferredReelsCheck = Runnable {
        if (!blockInstagramReels) return@Runnable
        if (System.currentTimeMillis() - lastReelsBlockMs < 2500) return@Runnable
        val root = rootInActiveWindow ?: return@Runnable
        try {
            val pkg = root.packageName?.toString()
            if (pkg != "com.instagram.android" && pkg != "com.instagram.lite") return@Runnable
            if (isReelsPlayerVisible(root)) {
                lastReelsBlockMs = System.currentTimeMillis()
                Toast.makeText(applicationContext, "🚫 Focus Mode: Instagram Reels blocked", Toast.LENGTH_SHORT).show()
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        } finally { root.recycle() }
    }

    private fun isShortsPlayerVisible(root: AccessibilityNodeInfo): Boolean {
        // Signal A: Try several known view IDs (varies by YouTube version).
        val candidateIds = listOf(
            "com.google.android.youtube:id/reel_recycler",
            "com.google.android.youtube:id/reel_player_view_container",
            "com.google.android.youtube:id/shorts_player_view",
            "com.google.android.youtube:id/shorts_container",
            "com.google.android.youtube:id/reel_player_page_container"
        )
        for (id in candidateIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                nodes.forEach { it.recycle() }
                return true
            }
            nodes.forEach { it.recycle() }
        }

        // Signal B: Recursive scan — catches any YouTube version by looking for any node
        // whose resource-ID or class name contains a Shorts/Reel player pattern.
        if (containsShortsNode(root, 0)) return true

        // Signal C: content description YouTube exposes on the player root.
        val descNodes = root.findAccessibilityNodeInfosByText("Shorts player")
        if (descNodes.isNotEmpty()) { descNodes.forEach { it.recycle() }; return true }
        descNodes.forEach { it.recycle() }

        // Signal D: Shorts tab selected in bottom nav (covers tap-tab entry).
        val shortsNodes = root.findAccessibilityNodeInfosByText("Shorts")
        val tabActive = shortsNodes.any { n ->
            n.isSelected || n.isChecked ||
            n.contentDescription?.toString()?.contains("selected", ignoreCase = true) == true
        }
        shortsNodes.forEach { it.recycle() }
        if (tabActive) return true

        // Signal E: Like/Dislike button text unique to the Shorts player (English UI).
        val playerNodes = root.findAccessibilityNodeInfosByText("this Short")
        val inPlayer = playerNodes.isNotEmpty()
        playerNodes.forEach { it.recycle() }
        return inPlayer
    }

    // Recursively walks the accessibility tree (max 8 levels deep) looking for any node
    // whose resource-ID or class name indicates a Shorts / Reel player.
    private fun containsShortsNode(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > 8) return false
        val id  = (node.viewIdResourceName ?: "").lowercase()
        val cls = (node.className?.toString() ?: "").lowercase()
        val combined = "$id|$cls"
        // Match any ID/class that pairs "reel" or "short(s)" with "player" or "container"
        val isPlayer = (combined.contains("reel") || combined.contains("short")) &&
                       (combined.contains("player") || combined.contains("container"))
        if (isPlayer) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = containsShortsNode(child, depth + 1)
            child.recycle()
            if (found) return true
        }
        return false
    }

    private fun isReelsPlayerVisible(root: AccessibilityNodeInfo): Boolean {
        val pkg = root.packageName?.toString() ?: ""
        val screenHeight = resources.displayMetrics.heightPixels

        if (pkg == "com.instagram.android") {
            // tab_bar is always in the accessibility tree even in fullscreen Reels — individual
            // tab children aren't exposed, so isSelected checks can't work. The only reliable
            // signal is clips_viewer_view_pager becoming isVisibleToUser (covers both entry points:
            // home-feed tap and Reels nav tab press).
            for (id in listOf(
                "com.instagram.android:id/clips_viewer_view_pager",
                "com.instagram.android:id/reel_viewer_root",
                "com.instagram.android:id/unified_reels_viewer"
            )) {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                val visible = nodes.any { it.isVisibleToUser }
                nodes.forEach { it.recycle() }
                if (visible) return true
            }
            return containsReelsNode(root, 0)
        }

        // Instagram Lite is React Native: on the home page nav items are opaque ViewGroups with
        // no text/desc. On the Reels page they switch to android.widget.Button with English
        // content descriptions — so Button+desc="Reels" in the bottom nav uniquely signals Reels.

        // Primary: Button with desc="Reels" in the bottom nav area → Reels section is active.
        if (searchTreeForNode(root, maxDepth = 10) { node ->
            if (node.className?.toString() != "android.widget.Button") return@searchTreeForNode false
            val b = android.graphics.Rect()
            node.getBoundsInScreen(b)
            if (b.top <= screenHeight * 0.90f) return@searchTreeForNode false
            node.contentDescription?.toString()?.equals("Reels", ignoreCase = true) == true
        }) return true

        // Supplementary: known Lite view IDs (may match on future app versions).
        for (id in listOf(
            "com.instagram.lite:id/clips_viewer_view_pager",
            "com.instagram.lite:id/reel_viewer_root",
            "com.instagram.lite:id/unified_reels_viewer"
        )) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            val visible = nodes.any { it.isVisibleToUser }
            nodes.forEach { it.recycle() }
            if (visible) return true
        }

        // Supplementary: recursive class/ID scan for any visible Reels-player node.
        return containsReelsNode(root, 0)
    }

    // Recursively walks the accessibility tree (max 8 levels) looking for any VISIBLE node
    // whose resource-ID or class name indicates an Instagram Reels player container.
    private fun containsReelsNode(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > 8) return false
        val id  = (node.viewIdResourceName ?: "").lowercase()
        val cls = (node.className?.toString() ?: "").lowercase()
        val combined = "$id|$cls"
        val isReelsPlayer = (combined.contains("reel") || combined.contains("clips")) &&
                            (combined.contains("viewer") || combined.contains("player") || combined.contains("pager"))
        if (isReelsPlayer && node.isVisibleToUser) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = containsReelsNode(child, depth + 1)
            child.recycle()
            if (found) return true
        }
        return false
    }

    // Generic depth-limited tree traversal. Walks every node and returns true as soon as
    // `condition` matches. Children are properly recycled regardless of outcome.
    private fun searchTreeForNode(
        node: AccessibilityNodeInfo,
        depth: Int = 0,
        maxDepth: Int = 10,
        condition: (AccessibilityNodeInfo) -> Boolean
    ): Boolean {
        if (depth > maxDepth) return false
        if (condition(node)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = searchTreeForNode(child, depth + 1, maxDepth, condition)
            child.recycle()
            if (found) return true
        }
        return false
    }

    private fun checkForYoutubeShorts(event: AccessibilityEvent): Boolean {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val className = event.className?.toString() ?: ""
            if (className.contains("short", ignoreCase = true)) {
                mainHandler.removeCallbacks(deferredShortsCheck)
                performGlobalAction(GLOBAL_ACTION_BACK)
                return true
            }
            // Shorts from home feed opens as a fragment overlay — schedule a deferred check
            // once the player UI has settled rather than reacting to the premature class event.
            mainHandler.removeCallbacks(deferredShortsCheck)
            mainHandler.postDelayed(deferredShortsCheck, 1000)
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            val now = System.currentTimeMillis()
            if (now - lastShortsCheckMs < 500) return false
            lastShortsCheckMs = now
        }
        val root = rootInActiveWindow ?: return false
        return try {
            val blocked = isShortsPlayerVisible(root)
            if (blocked) {
                mainHandler.post { Toast.makeText(applicationContext, "🚫 Focus Mode: YouTube Shorts blocked", Toast.LENGTH_SHORT).show() }
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            blocked
        } finally { root.recycle() }
    }

    private fun checkForInstagramReels(event: AccessibilityEvent): Boolean {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val className = event.className?.toString() ?: ""
            if (className.contains("reel", ignoreCase = true) ||
                className.contains("clips", ignoreCase = true)) {
                mainHandler.removeCallbacks(deferredReelsCheck)
                performGlobalAction(GLOBAL_ACTION_BACK)
                return true
            }
            mainHandler.removeCallbacks(deferredReelsCheck)
            mainHandler.postDelayed(deferredReelsCheck, 1000)
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            val now = System.currentTimeMillis()
            if (now - lastReelsCheckMs < 500) return false
            lastReelsCheckMs = now
        }
        val now2 = System.currentTimeMillis()
        if (now2 - lastReelsBlockMs < 2500) return false
        val root = rootInActiveWindow ?: return false
        return try {
            val blocked = isReelsPlayerVisible(root)
            if (blocked) {
                lastReelsBlockMs = System.currentTimeMillis()
                mainHandler.post { Toast.makeText(applicationContext, "🚫 Focus Mode: Instagram Reels blocked", Toast.LENGTH_SHORT).show() }
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            blocked
        } finally { root.recycle() }
    }

    // Returns true if the current URL is whitelisted (caller should skip keyword scan).
    private fun checkForBlockedWebsites(root: AccessibilityNodeInfo): Boolean {
        val pkg = root.packageName?.toString() ?: ""
        val freshUrl = findBrowserUrl(root)
        if (freshUrl != null) {
            // Prefer URLs that contain a path (not hostname-only). If Chrome collapses to
            // just "m.youtube.com" but we already know the full path, keep the richer cache.
            val fresherThanCache = freshUrl.contains("/") || lastKnownBrowserUrl.isBlank() ||
                !lastKnownBrowserUrl.startsWith(freshUrl.substringBefore("/"))
            if (fresherThanCache) {
                lastKnownBrowserUrl = freshUrl
                lastBrowserPackage = pkg
            }
        }
        // Fall back to cached URL when still in the same browser (SPA / full-screen navigation).
        val url = if (freshUrl != null && freshUrl.contains("/")) freshUrl
                  else if (pkg == lastBrowserPackage && lastKnownBrowserUrl.isNotBlank()) lastKnownBrowserUrl
                  else freshUrl ?: return false

        for (site in whitelistedWebsites) {
            if (url.contains(site.lowercase())) return true
        }
        if (blockYoutubeShorts && url.contains("youtube.com/shorts")) {
            redirectBrowserToHome("YouTube Shorts blocked"); return false
        }
        if (blockInstagramReels &&
            (url.contains("instagram.com/reels") || url.contains("instagram.com/reel/"))) {
            redirectBrowserToHome("Instagram Reels blocked"); return false
        }

        // When Chrome hides the URL path (only shows domain), fall back to web content detection.
        val isIgDomain = url.contains("instagram.com")
        val isYtDomain = url.contains("youtube.com")
        val sH = resources.displayMetrics.heightPixels
        val sW = resources.displayMetrics.widthPixels

        // Instagram Reels web (confirmed via logcat):
        //   Home page:  only a small View with desc='reels' sits in the bottom nav (top > 90%).
        //   Reels page: additionally a large Button ("{user} reels Follow {caption}") covers
        //               the content area (top < 90%, width > 50% of screen).
        //   The nav tab never satisfies both size conditions, so the check is false-positive-safe.
        if (blockInstagramReels && isIgDomain && !url.contains("instagram.com/reel")) {
            val reelsContent = searchTreeForNode(root, maxDepth = 15) { node ->
                val txt = node.text?.toString()?.lowercase() ?: ""
                if (!txt.contains("reel")) return@searchTreeForNode false
                val b = android.graphics.Rect()
                node.getBoundsInScreen(b)
                b.top < sH * 0.90f && b.width() > sW * 0.50f
            }
            if (reelsContent) { redirectBrowserToHome("Instagram Reels blocked"); return false }
        }

        // YouTube Shorts web: the Shorts player is fullscreen — the bottom nav (Home/Shorts/
        // Subscriptions tabs) disappears entirely and is replaced by player controls:
        //   • android.widget.SeekBar at bottom of screen (the video scrubber)
        //   • android.widget.Button desc='Subscribe to @...' (channel subscribe row)
        // Both sit in the bottom 15% of the screen, uniquely identifying the Shorts player.
        // Neither appears on the YouTube home page or regular video pages.
        if (blockYoutubeShorts && isYtDomain && !url.contains("youtube.com/short")) {
            val shortsPlayer = searchTreeForNode(root, maxDepth = 15) { node ->
                if (!node.isVisibleToUser) return@searchTreeForNode false
                val b = android.graphics.Rect()
                node.getBoundsInScreen(b)
                if (b.top <= sH * 0.85f) return@searchTreeForNode false
                val cls = node.className?.toString() ?: ""
                if (cls == "android.widget.SeekBar") return@searchTreeForNode true
                if (cls == "android.widget.Button") {
                    val desc = node.contentDescription?.toString() ?: ""
                    if (desc.startsWith("Subscribe to @", ignoreCase = true)) return@searchTreeForNode true
                }
                false
            }
            if (shortsPlayer) { redirectBrowserToHome("YouTube Shorts blocked"); return false }
        }
        for (site in blockedWebsites) {
            if (url.contains(site.lowercase())) {
                startWebsiteProtection("\"$site\" is blocked.\nFocus protection will navigate you away.")
                return true  // skip keyword scan on already-blocked pages
            }
        }
        if (adultBlockingLevel >= 1) {
            for (domain in adultDomains) {
                if (url.contains(domain)) {
                    startWebsiteProtection("Adult content is blocked.\nFocus protection will navigate you away.")
                    return true  // skip keyword scan
                }
            }
        }
        return false
    }

    // Finds the current URL from any supported browser's address bar.
    //
    // Chrome (and other browsers) split their UI into multiple AccessibilityWindows:
    // one for the browser chrome (toolbar/URL bar) and one for the web content (WebView).
    // rootInActiveWindow often gives us only the web content window, missing the URL bar.
    // We therefore search ALL accessible windows so we always find the toolbar window.
    private fun findBrowserUrl(root: AccessibilityNodeInfo): String? {
        // Try rootInActiveWindow first (fast path).
        val fromRoot = extractUrlFromWindowRoot(root)
        if (fromRoot != null) return fromRoot

        // Slow path: iterate every accessible window until we find one with a URL bar.
        try {
            windows?.forEach { window ->
                val windowRoot = window.root ?: return@forEach
                val url = extractUrlFromWindowRoot(windowRoot)
                windowRoot.recycle()
                if (url != null) return url
            }
        } catch (_: Exception) {}
        return null
    }

    private fun extractUrlFromWindowRoot(root: AccessibilityNodeInfo): String? {
        val ids = listOf(
            "com.android.chrome:id/url_bar",
            "com.android.chrome:id/location_bar_edit_text",
            "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "com.sec.android.app.sbrowser:id/url_bar",
            "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "com.microsoft.emmx:id/url_bar",
            "com.brave.browser:id/url_bar",
            "com.opera.browser:id/url_bar"
        )
        for (id in ids) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            val node = nodes.firstOrNull()
            // Chrome collapses the displayed URL to hostname-only (e.g. "m.youtube.com").
            // contentDescription often contains the full URL including the path — try it first.
            val urlFromDesc = node?.contentDescription?.toString()?.lowercase()?.trim()
            val urlFromText = node?.text?.toString()?.lowercase()?.trim()
            nodes.forEach { it.recycle() }
            val url = when {
                !urlFromDesc.isNullOrBlank() && urlFromDesc.contains("/") -> urlFromDesc  // has path
                !urlFromText.isNullOrBlank() && urlFromText.contains("/") -> urlFromText  // has path
                !urlFromDesc.isNullOrBlank() -> urlFromDesc  // hostname only fallback
                !urlFromText.isNullOrBlank() -> urlFromText
                else -> null
            }
            if (!url.isNullOrBlank()) return url
        }
        // Fallback: scan ALL text nodes for something URL-like.
        // Catches browsers without a stable URL bar view ID.
        return findUrlInAnyTextNode(root)
    }

    private fun findUrlInAnyTextNode(root: AccessibilityNodeInfo): String? {
        var found: String? = null
        searchTreeForNode(root) { node ->
            val t = (node.text ?: node.contentDescription)?.toString()?.lowercase()?.trim()
                ?: return@searchTreeForNode false
            val looksLikeUrl = t.startsWith("http") || t.startsWith("www.") ||
                t.startsWith("m.youtube") || t.startsWith("m.instagram") ||
                (t.contains("youtube.com") || t.contains("instagram.com"))
            if (looksLikeUrl && t.length < 300) {
                found = t
                true
            } else false
        }
        return found
    }

    private fun checkForBlockedKeywords(node: AccessibilityNodeInfo) {
        val matched = scanNodeForKeywords(node) ?: return
        val reason = if (blockedKeywords.any { it.equals(matched, ignoreCase = true) })
            "Keyword \"$matched\" is blocked"
        else
            "Adult content blocked"
        blockApp(reason)
    }

    private fun scanNodeForKeywords(node: AccessibilityNodeInfo): String? {
        val text = node.text?.toString()?.lowercase() ?: ""
        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""

        val wl = whitelistedKeywords
        if (wl.isNotEmpty() && (wl.any { text.contains(it) } || wl.any { contentDesc.contains(it) })) {
            return null
        }

        for (keyword in blockedKeywords) {
            if (text.contains(keyword.lowercase()) || contentDesc.contains(keyword.lowercase())) {
                return keyword
            }
        }

        if (adultBlockingLevel >= 2) {
            for (keyword in AdultBlockList.KEYWORDS) {
                if (text.contains(keyword) || contentDesc.contains(keyword)) {
                    return keyword
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val result = scanNodeForKeywords(child)
                child.recycle()
                if (result != null) return result
            }
        }
        return null
    }

    private fun getAppLabel(pkg: String): String {
        return try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0)
            ).toString()
        } catch (_: Exception) {
            pkg.substringAfterLast('.')
        }
    }

    private fun isPackageBlocked(packageName: String): Boolean {
        val pkgLower = packageName.lowercase()
        return blockedPackages.any { it.lowercase() == pkgLower }
    }

    private fun isPackageWhitelisted(packageName: String): Boolean {
        val pkgLower = packageName.lowercase()
        return whitelistedPackages.any { it.lowercase() == pkgLower }
    }

    private fun showBlockingOverlay(reason: String = "") {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(this)) return
        overlayCountdownEndMs = System.currentTimeMillis() + 3_000L
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.OPAQUE
            ).also { it.gravity = Gravity.TOP or Gravity.LEFT }

            val container = FrameLayout(this)
            container.setBackgroundColor(0xF2111827.toInt())

            val content = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(80, 0, 80, 0)
            }

            android.widget.TextView(this).apply {
                text = "Focus Protection Active"
                textSize = 20f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 20)
                content.addView(this)
            }

            if (reason.isNotEmpty()) {
                android.widget.TextView(this).apply {
                    text = reason
                    textSize = 14f
                    setTextColor(0xFFB0BEC5.toInt())
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, 40)
                    content.addView(this)
                }
            }

            val countdownView = android.widget.TextView(this).apply {
                text = "3"
                textSize = 72f
                setTextColor(0xFF7986CB.toInt())
                gravity = Gravity.CENTER
                content.addView(this)
            }

            val dismissBtn = android.widget.Button(this).apply {
                text = "Continue"
                visibility = View.GONE
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFF3F51B5.toInt())
                setPadding(60, 0, 60, 0)
                content.addView(this)
            }

            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
            container.addView(content, lp)

            dismissBtn.setOnClickListener {
                overlayManuallyClosed = true
                hideBlockingOverlay()
                // If no motivation is queued, clear the armed flag so polling stops.
                if (!motivationActive && lastMotivationUrl.isEmpty()) {
                    settingsProtectionArmed = false
                    isWebsiteProtection = false
                }
            }

            wm.addView(container, params)
            overlayView = container
            Log.w(TAG, "🛡️ Styled overlay shown: $reason")

            // Countdown ticks: 3 → 2 → 1 → dismiss button
            val h = Handler(Looper.getMainLooper())
            h.postDelayed({ if (overlayView != null) countdownView.text = "2" }, 1_000)
            h.postDelayed({ if (overlayView != null) countdownView.text = "1" }, 2_000)
            h.postDelayed({
                if (overlayView != null) {
                    countdownView.visibility = View.GONE
                    dismissBtn.visibility = View.VISIBLE
                }
            }, 3_000)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay: ${e.message}")
        }
    }

    private fun hideBlockingOverlay() {
        val view = overlayView ?: return
        overlayView = null
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
            Log.w(TAG, "🛡️ Blocking overlay hidden")
        } catch (_: Exception) {}
    }

    private fun startProtectionPolling() {
        protectionPollHandler.removeCallbacks(protectionPollRunnable)
        protectionPollHandler.post(protectionPollRunnable)
    }

    // Returns true if any visible window still shows the dangerous settings content that
    // triggered protection. Mirrors the detection logic in checkAndBlockSettingsScreen so
    // we stop firing back/home the moment the user is actually out of the danger zone.
    private fun isStillInDangerZone(): Boolean {
        try {
            val wins = windows ?: return false
            for (win in wins) {
                val winRoot = win.root ?: continue
                val pkg = winRoot.packageName?.toString() ?: ""
                if (!isSettingsPackage(pkg) || pkg == applicationContext.packageName) {
                    winRoot.recycle(); continue
                }
                val text = collectAllVisibleText(winRoot).lowercase()
                val mentionsApp = text.contains("focus blocker") ||
                    text.contains(applicationContext.packageName.lowercase()) ||
                    winRoot.findAccessibilityNodeInfosByText(ownAppLabel)
                        .also { nodes -> nodes.forEach { it.recycle() } }.isNotEmpty()
                winRoot.recycle()
                if (!mentionsApp) continue
                val isDirectPermissionPage = text.contains("use focus blocker") ||
                    text.contains("installed services") || text.contains("force stop") ||
                    text.contains("uninstall") || text.contains("autostart") ||
                    text.contains("auto start") || text.contains("background popup") ||
                    text.contains("display pop-up") || text.contains("pop-up windows") ||
                    text.contains("background start activity")
                val dangerous = when (settingsProtectionLevel) {
                    1 -> isDirectPermissionPage
                    2 -> true
                    else -> false
                }
                if (dangerous) return true
            }
        } catch (_: Exception) {}
        return false
    }

    // While polling, back out of any dangerous settings window.
    // Only fires when the page is still actually in the danger zone — stops on its own
    // once the user is clear (avoids wasting actions on already-safe pages).
    private fun tryDismissSettingsWindows() {
        if (!isStillInDangerZone()) return
        try {
            val wins = windows ?: return
            for (win in wins) {
                val winRoot = win.root ?: continue
                val pkg = winRoot.packageName?.toString() ?: ""
                if (isSettingsPackage(pkg) && pkg != applicationContext.packageName) {
                    clickBackInWindow(winRoot)
                    winRoot.recycle()
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    return
                }
                winRoot.recycle()
            }
        } catch (_: Exception) {}
    }

    // Re-checks the current browser URL against blocked sites / adult domains.
    // Used by the poll loop to decide whether to keep pressing Back.
    private fun isStillOnBlockedWebsite(): Boolean {
        try {
            val root = rootInActiveWindow
            val url = if (root != null) {
                try { findBrowserUrl(root) } finally { root.recycle() }
            } else null
            val urlLower = (url ?: lastKnownBrowserUrl).lowercase()
            if (urlLower.isBlank()) return false
            for (site in blockedWebsites) {
                if (urlLower.contains(site.lowercase())) return true
            }
            if (adultBlockingLevel >= 1) {
                for (domain in adultDomains) {
                    if (urlLower.contains(domain)) return true
                }
            }
        } catch (_: Exception) {}
        return false
    }

    private fun tryDismissBrowserPage() {
        if (!isStillOnBlockedWebsite()) return
        lastKnownBrowserUrl = ""  // clear so stale cache doesn't re-trigger after BACK lands
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    // Arms the overlay + poll loop for a blocked website. Reuses all the same machinery as
    // settings protection so the user sees the same countdown overlay and optional motivation.
    private fun startWebsiteProtection(reason: String) {
        if (settingsProtectionArmed) return  // don't interrupt an already-active protection
        isWebsiteProtection = true
        lastBlockReason = reason
        overlayManuallyClosed = false
        settingsProtectionArmed = true
        showBlockingOverlay(reason)
        startProtectionPolling()
        if (motivationOnBlock) {
            val allUrls = motivationVideos + motivationChannels +
                motivationGalleryVideos +
                (if (motivationPhrases.isNotEmpty()) listOf("allphrases://") else emptyList())
            if (allUrls.isNotEmpty()) {
                val url = allUrls.random()
                lastMotivationUrl = url
                motivationStartedAt = System.currentTimeMillis()
                FocusBlockerForegroundService.launchMotivation(applicationContext, url)
            } else {
                FocusBlockerForegroundService.launchMainActivity(applicationContext)
            }
        }
    }

    private var lastSettingsCheckMs = 0L

    private fun checkAndBlockSettingsScreen(skipThrottle: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!skipThrottle && now - lastSettingsCheckMs < 300) return
        lastSettingsCheckMs = now

        // Try the active window first (fast path for normal non-split-screen mode).
        // In split screen the settings pane may NOT be the focused window, so we also
        // scan all accessible windows — mirrors how findBrowserUrl works.
        // Keep the settings root alive so we can click its Back button before recycling.
        var activePackage = ""
        var windowText = ""
        var settingsInBackground = false
        var settingsRoot: AccessibilityNodeInfo? = null

        val rootNode = rootInActiveWindow
        val rootPkg = rootNode?.packageName?.toString() ?: ""
        if (rootNode != null && isSettingsPackage(rootPkg) && rootPkg != applicationContext.packageName) {
            windowText = collectAllVisibleText(rootNode).lowercase()
            activePackage = rootPkg
            settingsRoot = rootNode   // kept alive; caller must recycle
        } else {
            rootNode?.recycle()
            // Settings not in the active window — scan all windows (split-screen path).
            try {
                val wins = windows ?: emptyList()
                for (win in wins) {
                    val winRoot = win.root ?: continue
                    val pkg = winRoot.packageName?.toString() ?: ""
                    if (isSettingsPackage(pkg) && pkg != applicationContext.packageName) {
                        windowText = collectAllVisibleText(winRoot).lowercase()
                        activePackage = pkg
                        settingsInBackground = true
                        settingsRoot = winRoot  // kept alive; caller must recycle
                        break
                    }
                    winRoot.recycle()
                }
            } catch (_: Exception) {}
        }

        if (windowText.isEmpty()) { settingsRoot?.recycle(); return }

        val mentionsOurApp = windowText.contains("focus blocker") ||
            windowText.contains(applicationContext.packageName.lowercase()) ||
            settingsRoot?.findAccessibilityNodeInfosByText(ownAppLabel)
                ?.also { nodes -> nodes.forEach { it.recycle() } }?.isNotEmpty() == true

        if (!mentionsOurApp) { settingsRoot?.recycle(); return }

        // Pages with direct controls over Focus Blocker's permissions (Low + High)
        val isDirectPermissionPage = windowText.contains("use focus blocker") ||
            windowText.contains("installed services") ||
            windowText.contains("force stop") ||
            windowText.contains("uninstall") ||
            windowText.contains("autostart") ||
            windowText.contains("auto start") ||
            windowText.contains("background popup") ||
            windowText.contains("display pop-up") ||
            windowText.contains("pop-up windows") ||
            windowText.contains("background start activity")

        val shouldAct = when (settingsProtectionLevel) {
            1 -> isDirectPermissionPage   // Low: only pages with direct permission controls
            2 -> true                      // High: any settings page mentioning the app
            else -> false
        }

        if (!shouldAct) { settingsRoot?.recycle(); return }

        openedFromApp = true

        if (openedViaSettingsIcon()) {
            openedViaSettingsIconAt = 0L
            Log.w(TAG, "🛡️ Settings opened via icon — pressing Back")
            settingsRoot?.recycle()
            pressBackUntilLeavingSettings(attemptsLeft = 6)
            return
        }

        Log.w(TAG, "🛡️ Settings protection triggered (direct=$isDirectPermissionPage, level=$settingsProtectionLevel, splitScreen=$settingsInBackground)")

        // Immediately cover settings with an opaque overlay and start continuous polling.
        // settingsProtectionArmed must be set BEFORE startProtectionPolling() so the first
        // poll run (which fires immediately via Handler.post) sees the armed flag.
        lastBlockReason = if (isDirectPermissionPage)
            "This page controls FocusApp's critical permissions.\nChanging these will disable protection."
        else
            "This settings page references FocusApp.\nFocus protection is active."
        overlayManuallyClosed = false
        settingsProtectionArmed = true
        showBlockingOverlay(lastBlockReason)
        startProtectionPolling()

        fun launchMotivationForSettings() {
            if (!motivationOnSettings) return
            val allUrls = motivationVideos + motivationChannels +
                motivationGalleryVideos +
                (if (motivationPhrases.isNotEmpty()) listOf("allphrases://") else emptyList())
            if (allUrls.isNotEmpty()) {
                val url = allUrls.random()
                lastMotivationUrl = url
                motivationStartedAt = System.currentTimeMillis()
                FocusBlockerForegroundService.launchMotivation(applicationContext, url)
            } else {
                FocusBlockerForegroundService.launchMainActivity(applicationContext)
            }
        }

        if (isDirectPermissionPage && !settingsInBackground) {
            // Settings is focused — GLOBAL_ACTION_BACK navigates it back, then HOME.
            settingsRoot?.recycle()
            performGlobalAction(GLOBAL_ACTION_BACK)
            Handler(Looper.getMainLooper()).postDelayed({
                performGlobalAction(GLOBAL_ACTION_HOME)
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        val am = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                        am.killBackgroundProcesses(activePackage)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to kill $activePackage", e)
                    }
                    launchMotivationForSettings()
                    Handler(Looper.getMainLooper()).postDelayed({ openedFromApp = false }, 500)
                }, 200)
            }, 100)
        } else {
            // Split-screen or high-mode non-direct page.
            // Click the Back button directly on the settings window node — accessibility
            // services can perform actions on any window regardless of which pane has focus.
            settingsRoot?.let { clickBackInWindow(it) }
            settingsRoot?.recycle()

            val killAndMotivate = Runnable {
                try {
                    val am = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                    am.killBackgroundProcesses(activePackage)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to kill $activePackage", e)
                }
                launchMotivationForSettings()
                Handler(Looper.getMainLooper()).postDelayed({ openedFromApp = false }, 500)
            }

            if (settingsInBackground) {
                // Toggle split screen first, then wait for it to complete before going home.
                performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
                Handler(Looper.getMainLooper()).postDelayed({
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    Handler(Looper.getMainLooper()).postDelayed(killAndMotivate, 200)
                }, 300)
            } else {
                performGlobalAction(GLOBAL_ACTION_HOME)
                Handler(Looper.getMainLooper()).postDelayed(killAndMotivate, 200)
            }
        }
    }

    // Finds the settings "Navigate up / Back" button in the given window and clicks it.
    // Works on unfocused windows (split-screen background pane) because accessibility
    // services can perform node actions on any accessible window.
    private fun clickBackInWindow(root: AccessibilityNodeInfo): Boolean {
        return searchTreeForNode(root, maxDepth = 8) { node ->
            if (!node.isClickable) return@searchTreeForNode false
            val desc = node.contentDescription?.toString() ?: ""
            if (desc.equals("Navigate up", ignoreCase = true) ||
                desc.equals("Back", ignoreCase = true) ||
                desc.equals("Go back", ignoreCase = true)) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else false
        }
    }

    private fun isSettingsPackage(pkg: String) =
        pkg.contains("setting", ignoreCase = true) ||
        pkg.contains("securitycenter", ignoreCase = true) ||
        pkg.contains("systemmanager", ignoreCase = true) ||
        pkg.contains("permcenter", ignoreCase = true)

    private fun pressBackUntilLeavingSettings(attemptsLeft: Int) {
        if (attemptsLeft <= 0) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            Handler(Looper.getMainLooper()).postDelayed({ openedFromApp = false }, 800)
            return
        }
        performGlobalAction(GLOBAL_ACTION_BACK)
        Handler(Looper.getMainLooper()).postDelayed({
            val currentPkg = rootInActiveWindow?.packageName?.toString()
            when {
                currentPkg.isNullOrEmpty() -> pressBackUntilLeavingSettings(attemptsLeft - 1)
                isSettingsPackage(currentPkg) -> pressBackUntilLeavingSettings(attemptsLeft - 1)
                else -> Handler(Looper.getMainLooper()).postDelayed({ openedFromApp = false }, 600)
            }
        }, 400)
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

    private fun redirectBrowserToHome(reason: String) {
        lastKnownBrowserUrl = ""  // clear cache so stale URL doesn't re-trigger before page changes
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, "🚫 Focus Mode: $reason", Toast.LENGTH_SHORT).show()
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private fun blockApp(reason: String, pkg: String = "") {
        Log.w(TAG, "🚫 BLOCKING: $reason (pkg=$pkg)")
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, "🚫 Focus Mode: $reason", Toast.LENGTH_SHORT).show()
        }
        performGlobalAction(GLOBAL_ACTION_HOME)
        if (pkg.isNotBlank()) {
            Handler(Looper.getMainLooper()).postDelayed({ killPkg(pkg) }, 500)
            Handler(Looper.getMainLooper()).postDelayed({ killPkg(pkg) }, 1500)
            Handler(Looper.getMainLooper()).postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, 600)
            if (motivationOnBlock) {
                Handler(Looper.getMainLooper()).postDelayed({
                    val allUrls = motivationVideos + motivationChannels +
                        motivationGalleryVideos +
                        (if (motivationPhrases.isNotEmpty()) listOf("allphrases://") else emptyList())
                    if (allUrls.isNotEmpty()) {
                        val url = allUrls.random()
                        lastMotivationUrl = url
                        motivationStartedAt = System.currentTimeMillis()
                        FocusBlockerForegroundService.launchMotivation(applicationContext, url)
                    } else {
                        FocusBlockerForegroundService.launchMainActivity(applicationContext)
                    }
                }, 2000)
            }
        }
    }

    private fun killPkg(pkg: String) {
        try {
            val am = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.killBackgroundProcesses(pkg)
        } catch (e: Exception) {
            Log.e(TAG, "Kill failed for $pkg: ${e.message}")
        }
    }
}
