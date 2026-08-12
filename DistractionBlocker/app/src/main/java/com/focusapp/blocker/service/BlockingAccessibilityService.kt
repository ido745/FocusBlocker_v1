package com.focusapp.blocker.service

import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.Toast
import com.focusapp.blocker.data.AdultBlockList
import com.focusapp.blocker.data.AdultContentClassifier
import com.focusapp.blocker.data.PreferencesManager
import com.focusapp.blocker.receiver.FocusDeviceAdminReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BlockingAccessibilityService : AccessibilityService() {

    companion object {
        // App-initiated grant window. MainActivity sets this immediately before launching a
        // settings intent so the user can actually grant permissions. It is TIME-BOUNDED:
        // a stale `true` (eviction path aborted, activity killed, etc.) used to leave the
        // guard disarmed indefinitely, which was a standing hole.
        private const val GRANT_WINDOW_MS = 120_000L
        @Volatile private var openedFromAppAt = 0L
        var openedFromApp: Boolean
            get() = System.currentTimeMillis() - openedFromAppAt < GRANT_WINDOW_MS
            set(value) { openedFromAppAt = if (value) System.currentTimeMillis() else 0L }

        @Volatile var openedViaSettingsIconAt = 0L
        private const val SETTINGS_ICON_WINDOW_MS = 8_000L

        // Activity/window class fragments that identify a page capable of disabling us.
        // Class names are locale-independent, unlike the text heuristics below — this is what
        // keeps the guard working on a non-English device.
        private val DANGEROUS_CLASS_MARKERS = listOf(
            "accessibilitysettings", "accessibilitydetailssettings", "toggleaccessibilityservice",
            "accessibilityshortcut", "installedappdetails", "appinfodashboard",
            "applicationdetails", "manageapplications", "appmanagementactivity",
            "autostartmanagementactivity", "permissionseditoractivity", "permissionsedit",
            "permcenter", "deviceadmin", "runningservices", "specialaccess", "appopsdetails"
        )

        // Locale-independent identifiers for the app-details page and its Force stop /
        // Uninstall / Disable action buttons.
        private val DANGEROUS_VIEW_IDS = listOf(
            "com.android.settings:id/entity_header_title",
            "com.android.settings:id/uninstall_button",
            "com.android.settings:id/force_stop_button",
            "com.android.settings:id/left_button",
            "com.android.settings:id/right_button",
            "com.android.settings:id/button1",
            "com.android.settings:id/button2"
        )

        // English phrases kept as a supplementary signal on top of the structural checks.
        private val DIRECT_PERMISSION_MARKERS = listOf(
            "use lockin", "installed services", "force stop", "uninstall",
            "autostart", "auto start", "background popup", "display pop-up",
            "pop-up windows", "background start activity"
        )

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
        } catch (_: Exception) { "LockIn" }
    }

    // ─────────────────────────── THE SHIELD ───────────────────────────
    // A single full-screen window that is added to the WindowManager ONCE, at service
    // connect, and never removed. Arming it is a LayoutParams update (~1 frame) instead of
    // a cold addView (~100 ms+), which is what the user was out-racing.
    //
    // Window type is TYPE_ACCESSIBILITY_OVERLAY, not TYPE_APPLICATION_OVERLAY: since
    // Android 12 the Settings app calls hideNonSystemOverlayWindows() on precisely the
    // permission/accessibility screens we defend, which SUPPRESSED the old overlay on the
    // one page that mattered. Accessibility overlays are trusted and are not suppressed.
    // The shield runs on its OWN thread, with its own Looper and ViewRootImpl.
    //
    // It used to share the main thread with the app's Activity UI. Blocking a settings page
    // launches the motivation screen ~280 ms later, and that activity's cold start (Compose
    // inflation + WebView init) blocks the main thread for seconds — measured at 4.9 s,
    // "Skipped 295 frames". During that stall the shield could not draw at all, so the
    // countdown appeared frozen on "3" and Continue arrived seconds late. No timer logic can
    // fix that, because the whole UI thread is stopped.
    //
    // A window added from a thread with a Looper gets its ViewRootImpl on that thread, so
    // the shield now renders and ticks independently of anything the app does. Every view
    // touch below must therefore happen on shieldHandler — main-thread callers go through
    // the raiseShield/lowerShield wrappers, which post.
    private enum class ShieldMode { IDLE, CHECKING, BLOCKED }

    private val shieldThread = android.os.HandlerThread(
        "focus-shield", android.os.Process.THREAD_PRIORITY_DISPLAY
    ).apply { start() }
    private val shieldHandler = Handler(shieldThread.looper)

    private var shieldView: FrameLayout? = null
    @Volatile private var shieldMode = ShieldMode.IDLE
    private var shieldWindowType = 0
    private var shieldScrim: View? = null
    private var shieldPanel: android.widget.LinearLayout? = null
    private var shieldReasonView: android.widget.TextView? = null
    private var shieldCountdownView: android.widget.TextView? = null
    private var shieldDismissBtn: android.widget.Button? = null

    // CHECKING is now ALWAYS invisible. It stays fully touch-absorbing — that is the actual
    // protection — but never paints anything, so a slow classification no longer flashes a
    // blue scrim over the screen. Only a confirmed block (BLOCKED) is ever visible, and that
    // one is meant to be seen.
    //
    // Nothing about the guarantee changes: an unclassified settings page still cannot receive
    // a tap, because touch blocking depends on FLAG_NOT_TOUCHABLE, not on being drawn.

    @Volatile private var shieldCheckingSinceMs = 0L
    @Volatile private var overlayCountdownEndMs = 0L

    // Insurance. The shield covers the whole screen and swallows input, so no bug anywhere
    // in the state machine may be allowed to strand it. This sweep releases any shield that
    // nothing is justifying, and resolves a check that has run too long in either direction.
    private val shieldSafetyRunnable = object : Runnable {
        override fun run() {
            try {
                if (shieldMode == ShieldMode.CHECKING &&
                    System.currentTimeMillis() - shieldCheckingSinceMs > 1_200) {
                    if (isStillInDangerZone()) escalate(null) else { lowerShield(); leaveAirlock() }
                } else if (shieldMode != ShieldMode.IDLE &&
                    !settingsProtectionArmed && !airlockActive) {
                    Log.w(TAG, "🛡️ Safety sweep released an orphaned shield")
                    lowerShield()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Shield safety sweep failed: ${e.message}")
            }
            mainHandler.postDelayed(this, 600)
        }
    }

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
                // Protection ended (motivation timer elapsed or was not triggered).
                // Only release if the dangerous page is actually gone — but keep polling
                // if it isn't, so the shield can never be left up with no one driving it.
                if (isStillInDangerZone()) {
                    raiseShield(ShieldMode.BLOCKED, lastBlockReason)
                    tryDismissSettingsWindows()
                    protectionPollHandler.postDelayed(this, 200)
                    return
                }
                lowerShield()
                leaveAirlock()
                isWebsiteProtection = false
                return
            }
            val countdownDone = System.currentTimeMillis() >= overlayCountdownEndMs
            if (countdownDone && (motivationActive || overlayManuallyClosed)) {
                // Countdown finished and user dismissed or motivation is now playing.
                // Release the shield so the video (or home screen) is visible — but never
                // while the dangerous page is still up.
                if (!isStillInDangerZone()) lowerShield()
                protectionPollHandler.postDelayed(this, 200)
                return
            }
            // Keep the shield up — either countdown still running or waiting for motivation.
            raiseShield(ShieldMode.BLOCKED, lastBlockReason)
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
        // Pre-warm the shield window so arming it later is a relayout, not a window create.
        shieldHandler.post { installShield() }
        applyDeviceOwnerHardening()
        updateGuardExpected()
        mainHandler.removeCallbacks(shieldSafetyRunnable)
        mainHandler.postDelayed(shieldSafetyRunnable, 2_000)
    }

    // Tells the foreground-service watchdog whether this service is supposed to be running.
    // Tied to settings protection, so turning protection off (which already goes through the
    // 24-hour pending-change flow) is the only way to stop the recall nag.
    private fun updateGuardExpected() {
        try {
            applicationContext
                .getSharedPreferences("focus_guard", Context.MODE_PRIVATE)
                .edit().putBoolean("guard_expected", settingsProtectionLevel > 0).apply()
        } catch (_: Exception) {}
    }

    // With Device Owner these restrictions are enforced by the OS itself rather than by our
    // reaction time — Force stop / Clear data / Uninstall are greyed out at the source, and
    // the ADB and safe-mode escapes are closed. This is the only tier that is hermetic by
    // construction rather than by speed.
    private fun applyDeviceOwnerHardening() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (!dpm.isDeviceOwnerApp(packageName)) return
            val admin = ComponentName(this, FocusDeviceAdminReceiver::class.java)
            if (settingsProtectionLevel > 0) {
                for (restriction in listOf(
                    UserManager.DISALLOW_APPS_CONTROL,
                    UserManager.DISALLOW_UNINSTALL_APPS,
                    UserManager.DISALLOW_SAFE_BOOT,
                    UserManager.DISALLOW_DEBUGGING_FEATURES,
                    UserManager.DISALLOW_FACTORY_RESET,
                    UserManager.DISALLOW_ADD_USER
                )) {
                    try { dpm.addUserRestriction(admin, restriction) } catch (_: Exception) {}
                }
                // Only our accessibility service may run — blocks a rival service being
                // used to drive our toggle off.
                try { dpm.setPermittedAccessibilityServices(admin, listOf(packageName)) } catch (_: Exception) {}
                Log.w(TAG, "🔐 Device Owner hardening applied")
            } else {
                for (restriction in listOf(
                    UserManager.DISALLOW_APPS_CONTROL,
                    UserManager.DISALLOW_UNINSTALL_APPS,
                    UserManager.DISALLOW_SAFE_BOOT,
                    UserManager.DISALLOW_DEBUGGING_FEATURES,
                    UserManager.DISALLOW_FACTORY_RESET,
                    UserManager.DISALLOW_ADD_USER
                )) {
                    try { dpm.clearUserRestriction(admin, restriction) } catch (_: Exception) {}
                }
                try { dpm.setPermittedAccessibilityServices(admin, null) } catch (_: Exception) {}
                Log.w(TAG, "🔓 Device Owner hardening cleared")
            }
            // Uninstall blocking follows the deletion-protection setting, not this one.
            try { dpm.setUninstallBlocked(admin, packageName, deletionProtectionEnabled) } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.e(TAG, "DO hardening failed: ${e.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: "unknown"

        if (packageName == applicationContext.packageName) {
            // Our own UI is in front — an eviction landed, or the user opened the app. The
            // airlock is no longer inside Settings, so clear its state; otherwise it stays
            // stale until some other app happens to raise a window. Never releases a
            // confirmed block, which the countdown/motivation flow still owns.
            if (airlockActive && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                leaveAirlock()
            }
            return
        }

        // ── AIRLOCK: must stay first. Nothing is allowed to add latency to raising the
        // shield, so this runs before every other check, throttle, and tree read. ──
        if (settingsProtectionLevel > 0) {
            if (isSettingsPackage(packageName)) {
                if (!openedFromApp) {
                    when (event.eventType) {
                        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                            enterAirlock(packageName, event.className?.toString() ?: "",
                                event.eventTime)
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> probeAirlock()
                        else -> {}
                    }
                }
            } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                // A non-settings app took the foreground.
                //
                // There used to be a TYPE_WINDOWS_CHANGED branch here that re-armed the
                // shield whenever any settings window existed. On Settings home — which
                // emits a stream of these from other packages — it fought the release: arm,
                // classify, release, re-arm, producing the repeated dark-blue flash. It is
                // gone. Nothing is lost: every route to a settings page, including deep
                // links and in-app navigation, raises a settings TYPE_WINDOW_STATE_CHANGED,
                // which arms the shield above with no scanning and no throttle.
                if (airlockActive) leaveAirlock()
            }
        }

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
        mainHandler.removeCallbacks(shieldSafetyRunnable)
        shieldHandler.removeCallbacks(countdownTicker)
        protectionPollHandler.removeCallbacks(protectionPollRunnable)
        airlockHandler.removeCallbacks(airlockClassifyRunnable)
        // Tear the window down on the thread that owns it, then stop that thread.
        shieldHandler.post {
            destroyShield()
            shieldThread.quitSafely()
        }
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
                mainHandler.post { applyDeviceOwnerHardening() }
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
        // Adult blocking used to be configured ONLY by AuthViewModel when the app UI opened.
        // If the service started without that — after a reboot, a crash, or an update — the
        // level stayed 0 and adult blocking silently did nothing at any level. The service
        // now reads it directly, like every other setting.
        serviceScope.launch {
            preferencesManager.adultBlockingLevel.collect { adultBlockingLevel = it }
        }
        serviceScope.launch {
            val cached = AdultBlockList.loadCached(applicationContext)
            if (cached.isNotEmpty()) adultDomains = cached
        }
        serviceScope.launch {
            preferencesManager.settingsProtectionLevel.collect {
                settingsProtectionLevel = it
                mainHandler.post {
                    applyDeviceOwnerHardening()
                    updateGuardExpected()
                    if (it == 0) leaveAirlock()
                }
            }
        }
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
        // The user's own keyword list keeps its per-node "any match blocks" behaviour —
        // those terms were chosen deliberately — but now matches whole words, so "sex"
        // no longer fires on "Sussex" and "xxx" no longer fires inside a masked number.
        val matched = scanNodeForUserKeywords(node)
        if (matched != null) {
            blockApp("Keyword \"$matched\" is blocked")
            return
        }
        if (adultBlockingLevel >= 2) checkAdultContent(node)
    }

    // Cache of compiled whole-word patterns for the user's keyword list, rebuilt only when
    // that list actually changes.
    private var userKeywordPatterns: List<Pair<String, Regex>> = emptyList()
    private var userKeywordPatternSource: Set<String> = emptySet()

    private fun userPatterns(): List<Pair<String, Regex>> {
        val current = blockedKeywords
        if (current !== userKeywordPatternSource) {
            userKeywordPatternSource = current
            userKeywordPatterns = current.map {
                it to Regex("\\b" + Regex.escape(it.trim()) + "\\b", RegexOption.IGNORE_CASE)
            }
        }
        return userKeywordPatterns
    }

    private fun scanNodeForUserKeywords(node: AccessibilityNodeInfo): String? {
        val patterns = userPatterns()
        if (patterns.isEmpty()) return null
        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val combined = if (text.isEmpty()) contentDesc else "$text $contentDesc"

        if (combined.isNotBlank()) {
            val wl = whitelistedKeywords
            val lower = combined.lowercase()
            if (wl.isNotEmpty() && wl.any { lower.contains(it) }) return null
            for ((keyword, pattern) in patterns) {
                if (pattern.containsMatchIn(combined)) return keyword
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = scanNodeForUserKeywords(child)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    // Strict adult blocking. Instead of tripping on the first keyword found anywhere, the
    // whole screen is gathered — with each piece tagged by where it sits — and scored as one.
    private fun checkAdultContent(root: AccessibilityNodeInfo) {
        val pkg = root.packageName?.toString() ?: ""
        val screenHeight = resources.displayMetrics.heightPixels
        val collected = ScreenText()
        collectScreenText(root, collected, (screenHeight * 0.18f).toInt(), 0)

        val body = collected.body.toString()
        val title = collected.title.toString()

        // Abstain when there is nothing to read. Chrome frequently has no web content in the
        // accessibility tree yet — measured on device, a Wikipedia page arrived with body=''
        // and only the URL available, and the classifier blocked it on that alone. No
        // evidence must mean no verdict. Sites on the domain blocklist are unaffected: they
        // are handled by the exact-match path, not by this scorer.
        val bodyWords = body.split(Regex("\\s+")).count { it.isNotBlank() }
        if (bodyWords < 20) return

        val wl = whitelistedKeywords
        if (wl.isNotEmpty()) {
            val lower = (title + " " + body).lowercase()
            if (wl.any { lower.contains(it) }) return
        }

        // Only trust the cached URL while we are actually in that browser; otherwise it is
        // a leftover from an earlier session and would describe the wrong page entirely.
        val url = if (browserPackages.contains(pkg) && pkg == lastBrowserPackage) lastKnownBrowserUrl else ""
        val isAdultDomain = url.isNotBlank() && adultBlockingLevel >= 1 &&
            adultDomains.any { url.contains(it) }

        // Fail SAFE, never fatal. A malformed pattern in the classifier once threw inside
        // onAccessibilityEvent and killed the whole process, which took the accessibility
        // service down with it — the guard disappeared entirely because of a content-scoring
        // bug. Content classification is the least important thing this service does; it is
        // never allowed to bring the rest down.
        val verdict = try {
            AdultContentClassifier.classify(
                AdultContentClassifier.PageContext(
                    url = url,
                    packageName = pkg,
                    title = title,
                    body = body,
                    isKnownAdultDomain = isAdultDomain
                )
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Adult classifier failed, treating page as clean: ${e.message}")
            return
        }
        // Score and word count only — never the page text itself. Logging what is on the
        // user's screen would put their browsing into logcat for any app with log access.
        Log.d(TAG, "adult p=%.3f words=%d pkg=%s".format(verdict.probability, bodyWords, pkg))
        if (verdict.isAdult) {
            Log.w(TAG, "🚫 Adult content p=%.2f (%s) pkg=%s".format(verdict.probability, verdict.reason, pkg))
            blockApp("Adult content blocked")
        }
    }

    private class ScreenText {
        val title = StringBuilder()
        val body = StringBuilder()
        var budget = 20_000   // hard cap so a huge page cannot make this walk expensive
    }

    /**
     * Collects visible text, split by position: the top of the screen holds the URL bar,
     * page heading and app bar, which say what the page IS. Everything below is body — a
     * comment there is far weaker evidence than the same word in the heading.
     *
     * Editable fields are skipped entirely: their contents are whatever the user typed or a
     * one-time code (the "01XXXXX-XX02" case), never a property of the page.
     */
    private fun collectScreenText(node: AccessibilityNodeInfo, out: ScreenText, titleCutoffY: Int, depth: Int) {
        if (depth > 14 || out.budget <= 0) return
        val cls = node.className?.toString() ?: ""
        if (cls.contains("EditText") || node.isEditable || node.isPassword) return

        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        if (!text.isNullOrBlank() || !desc.isNullOrBlank()) {
            val piece = listOfNotNull(text, desc).joinToString(" ")
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            val sink = if (bounds.top in 0 until titleCutoffY) out.title else out.body
            sink.append(piece).append(' ')
            out.budget -= piece.length
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectScreenText(child, out, titleCutoffY, depth + 1)
            child.recycle()
            if (out.budget <= 0) return
        }
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

    private fun shieldParams(mode: ShieldMode): WindowManager.LayoutParams {
        val armed = mode != ShieldMode.IDLE
        // The shield NEVER takes key focus, in any mode.
        //
        // A focusable overlay owns the back key, and performGlobalAction(GLOBAL_ACTION_BACK)
        // delivers to the focused window — so the guard's own eviction Back was being sent
        // to the shield instead of to the browser or settings page it was trying to back
        // out of. Blocked websites stopped navigating away; settings eviction only still
        // worked because a HOME follows the Back and HOME is focus-independent.
        //
        // Nothing is lost by staying non-focusable: this is a WindowManager overlay, not an
        // activity, so Back never dismissed it anyway — it goes to the app underneath and
        // navigates that backwards, which is the direction the guard wants. Touch blocking
        // depends on FLAG_NOT_TOUCHABLE, which is independent of focus.
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (!armed) {
            // Idle: 1×1 px, invisible, transparent to touch as well. Costs nothing.
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        return WindowManager.LayoutParams(
            if (armed) WindowManager.LayoutParams.MATCH_PARENT else 1,
            if (armed) WindowManager.LayoutParams.MATCH_PARENT else 1,
            shieldWindowType,
            flags,
            PixelFormat.TRANSLUCENT
        ).also { it.gravity = Gravity.TOP or Gravity.LEFT }
    }

    // Builds the view tree once. Both states (checking scrim / blocked panel) live in the
    // same hierarchy so switching between them never touches the WindowManager.
    private fun buildShieldView(): FrameLayout {
        // Plain FrameLayout: no key handling at all. Swallowing Back here was pointless
        // (a non-focusable window never receives it) and actively harmful when the window
        // was focusable — see the comment in shieldParams().
        val root = FrameLayout(this)

        // Every touch the shield swallows is logged with the mode it was in. This is the
        // only direct evidence that a tap aimed at Settings was actually intercepted rather
        // than merely arriving after the page was evicted — the two look identical from the
        // outside. Cheap: fires on ACTION_DOWN only, and only while the shield is armed.
        root.setOnTouchListener { _, ev ->
            if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                Log.w(TAG, "🛡️ ABSORBED touch mode=$shieldMode at ${ev.rawX.toInt()},${ev.rawY.toInt()}")
            }
            true
        }

        val scrim = View(this).apply {
            setBackgroundColor(0xF2111827.toInt())
            alpha = 0f
        }
        root.addView(scrim, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(80, 0, 80, 0)
            visibility = View.GONE
        }

        android.widget.TextView(this).apply {
            text = "Focus Protection Active"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20)
            content.addView(this)
        }

        val reasonView = android.widget.TextView(this).apply {
            textSize = 14f
            setTextColor(0xFFB0BEC5.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
            content.addView(this)
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

        dismissBtn.setOnClickListener {
            // This fires on the shield thread; the guard state it touches is owned by the
            // main thread, so hop over before deciding anything.
            mainHandler.post {
                // Refuse to release while the dangerous page is still on screen — otherwise
                // "Continue" is itself a way through the shield.
                if (isStillInDangerZone()) {
                    escalate("Still on a protected settings page.")
                    return@post
                }
                overlayManuallyClosed = true
                lowerShield()
                if (!motivationActive && lastMotivationUrl.isEmpty()) {
                    settingsProtectionArmed = false
                    isWebsiteProtection = false
                }
            }
        }

        root.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER })

        shieldScrim = scrim
        shieldPanel = content
        shieldReasonView = reasonView
        shieldCountdownView = countdownView
        shieldDismissBtn = dismissBtn
        return root
    }

    // Adds the shield window once. Retries on every raise until it succeeds, so a failure
    // at connect time (service not yet fully bound) is not permanent.
    private fun installShield() {
        if (shieldView != null) return
        val root = buildShieldView()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val types = intArrayOf(
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        )
        for (type in types) {
            if (type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY &&
                !Settings.canDrawOverlays(this)) continue
            try {
                shieldWindowType = type
                wm.addView(root, shieldParams(ShieldMode.IDLE))
                shieldView = root
                Log.w(TAG, "🛡️ Shield installed (windowType=$type)")
                return
            } catch (e: Exception) {
                Log.e(TAG, "Shield install failed for type=$type: ${e.message}")
            }
        }
    }

    // ── Thread boundary ──────────────────────────────────────────────────────────
    // Callers on the main thread use these; everything below the line runs on the
    // shield thread and is the only code allowed to touch the shield's views.
    private fun raiseShield(mode: ShieldMode, reason: String = "") {
        shieldHandler.post { raiseShieldOnThread(mode, reason) }
    }

    private fun lowerShield() {
        shieldHandler.post { lowerShieldOnThread() }
    }

    private fun raiseShieldOnThread(mode: ShieldMode, reason: String = "") {
        if (mode == ShieldMode.IDLE) return
        installShield()
        val view = shieldView ?: return
        // Never downgrade a confirmed block back to a provisional check.
        if (mode == ShieldMode.CHECKING && shieldMode == ShieldMode.BLOCKED) return
        if (mode == ShieldMode.BLOCKED && shieldMode == ShieldMode.BLOCKED) return
        // Already checking: keep the existing reveal timer running rather than restarting it,
        // so a page that fires several window events doesn't stay invisibly armed forever.
        if (mode == ShieldMode.CHECKING && shieldMode == ShieldMode.CHECKING) return
        // CHECKING and BLOCKED now carry identical window params, so only an IDLE→armed
        // transition needs a relayout. Skipping the redundant one removes a WindowManager
        // round-trip from the critical CHECKING→BLOCKED path, which is exactly the moment a
        // forbidden page is being blocked.
        val wasIdle = shieldMode == ShieldMode.IDLE
        shieldMode = mode
        if (mode == ShieldMode.CHECKING) shieldCheckingSinceMs = System.currentTimeMillis()
        try {
            if (wasIdle) {
                (getSystemService(WINDOW_SERVICE) as WindowManager)
                    .updateViewLayout(view, shieldParams(mode))
            }
            applyShieldMode(mode, reason)
        } catch (e: Exception) {
            Log.e(TAG, "raiseShield failed: ${e.message}")
        }
    }

    private fun applyShieldMode(mode: ShieldMode, reason: String) {
        val scrim = shieldScrim ?: return
        when (mode) {
            ShieldMode.CHECKING -> {
                // Invisible, always. Absorbs touches without painting anything.
                shieldPanel?.visibility = View.GONE
                scrim.animate().cancel()
                scrim.alpha = 0f
            }
            ShieldMode.BLOCKED -> {
                scrim.animate().cancel()
                scrim.alpha = 1f
                shieldReasonView?.text = reason
                shieldReasonView?.visibility = if (reason.isEmpty()) View.GONE else View.VISIBLE
                shieldPanel?.visibility = View.VISIBLE
                startShieldCountdown()
                Log.w(TAG, "🛡️ Shield BLOCKED: $reason")
            }
            ShieldMode.IDLE -> {}
        }
    }

    // Self-correcting countdown.
    //
    // This used to be three fixed postDelayed callbacks at 1s/2s/3s. They share the main
    // thread with window classification, which does an IPC per node — so when that ran long
    // the ticks arrived late and the display sat on "3" well past three seconds, with the
    // Continue button appearing even later.
    //
    // Now every tick recomputes from the deadline, so a stalled main thread makes the
    // number jump (3 → 1) instead of freezing, and the button appears as soon as the
    // deadline has actually passed regardless of how many ticks were missed.
    private val countdownTicker = object : Runnable {
        override fun run() {
            if (shieldMode != ShieldMode.BLOCKED) return
            val cd = shieldCountdownView ?: return
            val btn = shieldDismissBtn ?: return
            val remaining = overlayCountdownEndMs - System.currentTimeMillis()
            if (remaining <= 0) {
                cd.visibility = View.GONE
                btn.visibility = View.VISIBLE
                // Measures the actual wait the user sees. Should sit just over 3000 ms;
                // anything well above that means the main thread is being starved again.
                Log.w(TAG, "⏱️ Continue shown after " +
                    "${System.currentTimeMillis() - (overlayCountdownEndMs - 3_000L)}ms")
                return
            }
            val secs = ((remaining + 999) / 1000).toInt()
            val text = secs.toString()
            if (cd.text != text) cd.text = text
            shieldHandler.postDelayed(this, 100)
        }
    }

    private fun startShieldCountdown() {
        overlayCountdownEndMs = System.currentTimeMillis() + 3_000L
        val cd = shieldCountdownView ?: return
        val btn = shieldDismissBtn ?: return
        cd.visibility = View.VISIBLE
        cd.text = "3"
        btn.visibility = View.GONE
        shieldHandler.removeCallbacks(countdownTicker)
        shieldHandler.postDelayed(countdownTicker, 100)
    }

    // Returns the shield to its idle 1×1 state. The window itself is never removed, so the
    // next raise is a single relayout rather than a window creation.
    private fun lowerShieldOnThread() {
        val view = shieldView ?: return
        if (shieldMode == ShieldMode.IDLE) return
        shieldMode = ShieldMode.IDLE
        shieldHandler.removeCallbacks(countdownTicker)
        try {
            shieldScrim?.animate()?.cancel()
            shieldScrim?.alpha = 0f
            shieldPanel?.visibility = View.GONE
            (getSystemService(WINDOW_SERVICE) as WindowManager)
                .updateViewLayout(view, shieldParams(ShieldMode.IDLE))
        } catch (e: Exception) {
            Log.e(TAG, "lowerShield failed: ${e.message}")
        }
    }

    private fun destroyShield() {
        val view = shieldView ?: return
        shieldView = null
        shieldMode = ShieldMode.IDLE
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
        } catch (_: Exception) {}
    }

    private fun startProtectionPolling() {
        protectionPollHandler.removeCallbacks(protectionPollRunnable)
        protectionPollHandler.post(protectionPollRunnable)
    }

    // Single source of truth for "is a dangerous settings page on screen right now",
    // shared by the poll loop, the hard-lock watchdog and the Continue button.
    //
    // Briefly cached: the poll called this twice per iteration (once directly, once inside
    // tryDismissSettingsWindows), and each call enumerates every window and does IPC per
    // window. At 200 ms intervals that saturated the main thread and starved the countdown.
    // 250 ms of staleness is harmless here — the poll is only a backstop, and the primary
    // detection path is the window-state event, which is instant and never cached.
    private var dangerCacheAtMs = 0L
    private var dangerCacheValue = false

    private fun isStillInDangerZone(): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - dangerCacheAtMs < 250) return dangerCacheValue
        dangerCacheAtMs = now
        dangerCacheValue = classifyCurrentSettingsWindow() == Verdict.DANGEROUS
        return dangerCacheValue
    }

    private fun invalidateDangerCache() { dangerCacheAtMs = 0L }

    // While polling, back out of any dangerous settings window.
    // Only fires when the page is still actually in the danger zone — stops on its own
    // once the user is clear (avoids wasting actions on already-safe pages).
    // Counts consecutive dismiss attempts so the expensive node search runs only while it
    // still has a chance of helping.
    private var dismissAttempts = 0

    private fun tryDismissSettingsWindows() {
        if (!isStillInDangerZone()) { dismissAttempts = 0; return }
        dismissAttempts++
        // Global actions first: they are cheap, focus-independent, and do the actual work.
        performGlobalAction(GLOBAL_ACTION_BACK)
        performGlobalAction(GLOBAL_ACTION_HOME)
        invalidateDangerCache()
        // The node-level Back click is a fallback for panes the global action misses (split
        // screen). It walks the tree with an IPC per node, so it must not run on every
        // 200 ms poll — that was a large part of what starved the countdown.
        if (dismissAttempts > 2) return
        try {
            val wins = windows ?: return
            for (win in wins) {
                val winRoot = win.root ?: continue
                val pkg = winRoot.packageName?.toString() ?: ""
                if (isSettingsPackage(pkg) && pkg != applicationContext.packageName) {
                    clickBackInWindow(winRoot)
                    winRoot.recycle()
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
        raiseShield(ShieldMode.BLOCKED, reason)
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

    // ═══════════════════════════ THE AIRLOCK ═══════════════════════════
    //
    // Inverted control flow. The old design was fail-OPEN: the page stayed live and
    // interactive while collectAllVisibleText() walked the whole tree to prove it was
    // dangerous — 300-600 ms of tappable Settings once the framework's notificationTimeout
    // and the 300 ms throttle were added on top. That window is what a fast tap beat.
    //
    // This design is fail-CLOSED. Every settings window raises the shield BEFORE a single
    // node is read; safety must then be proven, twice, to release it. The invariant is:
    // a settings page is only ever tappable after it has been classified safe. Reaction
    // speed stops mattering because there is no interval in which an unclassified page
    // accepts input.

    // SAFE_CLEAN: a fully-built settings window that contains no reference to this app at
    // all. Nothing on such a page can disable us, so it is released on the FIRST pass —
    // that is the common case (Wi-Fi, display, sound…) and keeping it fast is what makes
    // ordinary Settings use feel normal.
    // SAFE: mentions us but is not dangerous — released only after two clean passes.
    private enum class Verdict { SAFE_CLEAN, SAFE, DANGEROUS, UNKNOWN, GONE }

    private var airlockActive = false
    private var airlockBlocked = false
    private var airlockSafeStreak = 0
    private var airlockUnknownSinceMs = 0L
    private var lastAirlockProbeMs = 0L
    private var evictionUntilMs = 0L
    private var currentSettingsClassName = ""
    private var lastSettingsPackage = ""

    private val airlockHandler = Handler(Looper.getMainLooper())

    private var classifyPending = false

    private val airlockClassifyRunnable = object : Runnable {
        override fun run() {
            classifyPending = false
            if (!airlockActive) return
            when (classifyCurrentSettingsWindow()) {
                Verdict.GONE -> { leaveAirlock(); return }
                Verdict.DANGEROUS -> { escalate(null); return }
                // Both safe verdicts need two consecutive passes, since a half-built page
                // can read as harmless on a single one. At 40 ms apart that resolves in
                // ~80 ms — comfortably under the shield's reveal delay, so a safe page is
                // released before the scrim ever becomes visible.
                Verdict.SAFE_CLEAN, Verdict.SAFE -> {
                    airlockUnknownSinceMs = 0L
                    airlockSafeStreak++
                    if (airlockSafeStreak >= 2) { releaseCheckingShield(); return }
                }
                Verdict.UNKNOWN -> {
                    airlockSafeStreak = 0
                    val now = System.currentTimeMillis()
                    if (airlockUnknownSinceMs == 0L) airlockUnknownSinceMs = now
                    // A settings window we cannot read is not a window we can call safe.
                    if (now - airlockUnknownSinceMs > 2_000) {
                        escalate("This settings page could not be verified.")
                        return
                    }
                }
            }
            scheduleClassify(40)
        }
    }

    // Only ever releases a provisional check — never a confirmed block, which is owned by
    // the eviction/countdown flow. Deliberately not gated on settingsProtectionArmed: that
    // flag can outlive its flow, and gating on it stranded the shield up on safe pages.
    private fun releaseCheckingShield() {
        if (shieldMode == ShieldMode.CHECKING) lowerShield()
    }

    // Self-rescheduling with a pending flag: a burst of window events (MIUI fires several
    // per navigation) must not keep cancelling and re-posting classification, which would
    // starve it and leave the shield up indefinitely.
    private fun scheduleClassify(delayMs: Long) {
        if (classifyPending) return
        classifyPending = true
        airlockHandler.postDelayed(airlockClassifyRunnable, delayMs)
    }

    // Called on every window-state change inside a settings package. Zero scanning, zero
    // throttling — this must be the cheapest possible path to a raised shield.
    private fun enterAirlock(settingsPkg: String, className: String, eventTime: Long = 0L) {
        if (settingsPkg.isNotEmpty()) lastSettingsPackage = settingsPkg
        currentSettingsClassName = className
        airlockActive = true
        airlockSafeStreak = 0
        airlockUnknownSinceMs = 0L
        raiseShield(ShieldMode.CHECKING)
        // Exposure window: system timestamp of the window change → shield relayout issued.
        // This is the interval a tap would have to land in, and it is the number that
        // decides whether the guard is beatable by speed. Lower bound: it excludes the
        // compositor actually applying the new touchable region.
        if (eventTime > 0L) {
            Log.w(TAG, "⏱️ armed ${android.os.SystemClock.uptimeMillis() - eventTime}ms " +
                "after window event ($className)")
        }

        // Class names are locale-independent, so this catches the accessibility and
        // app-info pages instantly even on a non-English device, before any classification.
        if (isDangerousClassName(className)) {
            escalate(null)
            return
        }
        scheduleClassify(0)
    }

    // Cheap probe for content changes inside an already-safe settings window: catches a page
    // that mutates into a dangerous one (MIUI loads app-info controls lazily) without
    // re-flashing the shield on every scroll. findAccessibilityNodeInfosByText is a native
    // query, not a tree walk, so this is affordable on every event.
    private fun probeAirlock() {
        if (airlockActive && shieldMode != ShieldMode.IDLE) return
        val now = System.currentTimeMillis()
        if (now - lastAirlockProbeMs < 250) return
        lastAirlockProbeMs = now
        if (settingsWindowMentionsOurApp()) enterAirlock(lastSettingsPackage, currentSettingsClassName)
    }

    private fun cancelClassify() {
        airlockHandler.removeCallbacks(airlockClassifyRunnable)
        classifyPending = false
    }

    private fun leaveAirlock() {
        if (!airlockActive) return
        airlockActive = false
        airlockBlocked = false
        airlockSafeStreak = 0
        airlockUnknownSinceMs = 0L
        currentSettingsClassName = ""
        cancelClassify()
        // Never yanks a confirmed block — that shield belongs to the eviction/countdown flow.
        releaseCheckingShield()
    }

    private fun isDangerousClassName(className: String): Boolean {
        if (className.isEmpty()) return false
        val c = className.lowercase()
        return DANGEROUS_CLASS_MARKERS.any { c.contains(it) }
    }

    private fun settingsWindowMentionsOurApp(): Boolean {
        try {
            val wins = windows ?: return false
            for (win in wins) {
                val root = try { win.root } catch (_: Exception) { null } ?: continue
                val pkg = root.packageName?.toString() ?: ""
                if (!isSettingsPackage(pkg) || pkg == applicationContext.packageName) {
                    root.recycle(); continue
                }
                val hit = mentionsOurApp(root)
                root.recycle()
                if (hit) return true
            }
        } catch (_: Exception) {}
        return false
    }

    private fun mentionsOurApp(root: AccessibilityNodeInfo): Boolean {
        val byLabel = root.findAccessibilityNodeInfosByText(ownAppLabel)
        val hasLabel = byLabel.isNotEmpty()
        byLabel.forEach { it.recycle() }
        if (hasLabel) return true
        val byPkg = root.findAccessibilityNodeInfosByText(applicationContext.packageName)
        val hasPkg = byPkg.isNotEmpty()
        byPkg.forEach { it.recycle() }
        return hasPkg
    }

    // Locale-independent: any visible checkable node is something that can switch us off.
    private fun hasInteractiveToggle(root: AccessibilityNodeInfo): Boolean =
        searchTreeForNode(root, maxDepth = 14) { n ->
            if (!n.isVisibleToUser) return@searchTreeForNode false
            if (n.isCheckable) return@searchTreeForNode true
            val c = n.className?.toString() ?: ""
            c.endsWith("Switch") || c.endsWith("SwitchCompat") ||
                c.endsWith("ToggleButton") || c.endsWith("CheckBox") ||
                c.endsWith("CompoundButton")
        }

    // View IDs are locale-independent, unlike "Force stop" / "Uninstall" text. These identify
    // the app-details page, whose action buttons are not checkable and so are invisible to
    // hasInteractiveToggle(). Without this the Low level had a hole on any non-English device.
    private fun hasDangerousViewId(root: AccessibilityNodeInfo): Boolean {
        for (id in DANGEROUS_VIEW_IDS) {
            val nodes = try { root.findAccessibilityNodeInfosByViewId(id) } catch (_: Exception) { null }
                ?: continue
            val present = nodes.isNotEmpty()
            nodes.forEach { it.recycle() }
            if (present) return true
        }
        return false
    }

    // Called only once the page is known to reference this app.
    private fun isDangerousGivenMention(root: AccessibilityNodeInfo, pkg: String): Boolean {
        if (settingsProtectionLevel >= 2) return true
        // Low level. Every MIUI security-centre page that names us is a permission editor.
        if (pkg.contains("securitycenter", true) || pkg.contains("permcenter", true) ||
            pkg.contains("systemmanager", true)) return true
        if (hasInteractiveToggle(root)) return true
        if (hasDangerousViewId(root)) return true
        val text = collectAllVisibleText(root).lowercase()
        return DIRECT_PERMISSION_MARKERS.any { text.contains(it) }
    }

    // An empty window is still being built; calling that safe would reopen the original
    // race. This MUST stay cheap — it runs on every classification pass. It used to walk
    // the tree looking for text, which costs one IPC per node and took longer than the
    // shield's reveal timer on a large page like Settings home, so the scrim flashed.
    // childCount is a single call, and the two-pass rule below covers a half-built tree.
    private fun isWindowPopulated(root: AccessibilityNodeInfo): Boolean = root.childCount > 0

    private fun classifyCurrentSettingsWindow(): Verdict {
        val wins = try { windows } catch (_: Exception) { null } ?: return Verdict.UNKNOWN
        if (wins.isEmpty()) return Verdict.UNKNOWN
        var anyRootReadable = false
        var foundSettings = false
        var notReady = false
        var sawMention = false
        for (win in wins) {
            val root = try { win.root } catch (_: Exception) { null } ?: continue
            anyRootReadable = true
            val pkg = root.packageName?.toString() ?: ""
            if (!isSettingsPackage(pkg) || pkg == applicationContext.packageName) {
                root.recycle(); continue
            }
            foundSettings = true
            lastSettingsPackage = pkg
            // The remembered activity class is only meaningful while a settings window is
            // actually on screen. This check used to short-circuit the whole function
            // before the loop, so a stale dangerous class name kept reporting DANGEROUS
            // after the user had already been evicted — which would have stranded the
            // shield up, made the Continue button refuse, and fired a spurious screen lock.
            if (isDangerousClassName(currentSettingsClassName)) {
                root.recycle()
                return Verdict.DANGEROUS
            }
            try {
                if (!isWindowPopulated(root)) {
                    notReady = true
                } else if (mentionsOurApp(root)) {
                    sawMention = true
                    if (isDangerousGivenMention(root, pkg)) { root.recycle(); return Verdict.DANGEROUS }
                }
            } catch (_: Exception) { notReady = true }
            root.recycle()
        }
        return when {
            !foundSettings && anyRootReadable -> Verdict.GONE
            !foundSettings -> Verdict.UNKNOWN
            notReady -> Verdict.UNKNOWN
            sawMention -> Verdict.SAFE
            else -> Verdict.SAFE_CLEAN
        }
    }

    private fun escalate(reasonOverride: String?) {
        airlockBlocked = true
        airlockActive = true
        cancelClassify()

        // The shield and the poll loop are (re)established unconditionally — only the
        // eviction actions below are rate-limited. Throttling the shield too would leave a
        // re-entry within the cooldown covered by nothing.
        lastBlockReason = reasonOverride
            ?: "This page controls FocusApp's critical permissions.\nChanging these will disable protection."
        overlayManuallyClosed = false
        settingsProtectionArmed = true
        raiseShield(ShieldMode.BLOCKED, lastBlockReason)
        startProtectionPolling()

        val now = System.currentTimeMillis()
        if (now < evictionUntilMs) return   // an eviction is already in flight
        evictionUntilMs = now + 2_500
        scheduleHardLock()

        val activePackage = lastSettingsPackage
        val settingsInBackground = rootInActiveWindow?.let { r ->
            val p = r.packageName?.toString() ?: ""
            r.recycle()
            !isSettingsPackage(p)
        } ?: false

        Log.w(TAG, "🛡️ Settings protection triggered (class=$currentSettingsClassName, " +
            "level=$settingsProtectionLevel, splitScreen=$settingsInBackground)")

        if (openedViaSettingsIcon()) {
            openedViaSettingsIconAt = 0L
            Log.w(TAG, "🛡️ Settings opened via icon — pressing Back")
            pressBackUntilLeavingSettings(attemptsLeft = 6)
            return
        }

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

        val killAndMotivate = Runnable {
            try {
                val am = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                am.killBackgroundProcesses(activePackage)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to kill $activePackage", e)
            }
            launchMotivationForSettings()
        }

        if (settingsInBackground) {
            // Collapse split screen first, then wait for it to complete before going home.
            performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
            Handler(Looper.getMainLooper()).postDelayed({
                performGlobalAction(GLOBAL_ACTION_HOME)
                Handler(Looper.getMainLooper()).postDelayed(killAndMotivate, 200)
            }, 300)
        } else {
            // Leave first, search later. Back and Home are cheap system actions; the
            // node-level Back click below walks the tree with an IPC per node, and running
            // it first delayed the visible eviction by however long that walk took.
            performGlobalAction(GLOBAL_ACTION_BACK)
            Handler(Looper.getMainLooper()).postDelayed({
                performGlobalAction(GLOBAL_ACTION_HOME)
                Handler(Looper.getMainLooper()).postDelayed(killAndMotivate, 200)
            }, 80)
        }
        invalidateDangerCache()

        // Fallback for panes a global Back cannot reach (split-screen background pane).
        // Posted, so it never sits on the critical path to getting the user out.
        Handler(Looper.getMainLooper()).post { clickBackInSettingsWindows() }
    }

    // Last-resort eviction. Fires only if the dangerous page is STILL on screen after the
    // normal back/home sequence — i.e. only against someone actively fighting the guard.
    // Locking the screen ends the input session outright: there is no overlay to suppress,
    // no window to tap through, and Settings is backgrounded by the time it unlocks.
    private fun scheduleHardLock() {
        airlockHandler.postDelayed({
            if (isStillInDangerZone()) hardLock("danger zone survived first eviction")
        }, 700)
        airlockHandler.postDelayed({
            if (isStillInDangerZone()) hardLock("danger zone persisted")
        }, 1_600)
    }

    private fun hardLock(why: String) {
        Log.w(TAG, "🔒 Hard lock: $why")
        var locked = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locked = try { performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) } catch (_: Exception) { false }
        }
        if (!locked) {
            try {
                val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                if (dpm.isAdminActive(ComponentName(this, FocusDeviceAdminReceiver::class.java))) {
                    dpm.lockNow()
                }
            } catch (e: Exception) {
                Log.e(TAG, "lockNow failed: ${e.message}")
            }
        }
    }

    private fun clickBackInSettingsWindows() {
        try {
            val wins = windows ?: return
            for (win in wins) {
                val root = try { win.root } catch (_: Exception) { null } ?: continue
                val pkg = root.packageName?.toString() ?: ""
                if (isSettingsPackage(pkg) && pkg != applicationContext.packageName) {
                    clickBackInWindow(root)
                }
                root.recycle()
            }
        } catch (_: Exception) {}
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
