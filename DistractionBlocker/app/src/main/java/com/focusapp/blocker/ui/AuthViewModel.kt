package com.focusapp.blocker.ui

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.os.Build
import androidx.core.app.NotificationCompat
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.focusapp.blocker.data.AdultBlockList
import com.focusapp.blocker.data.MotivationConfig
import com.focusapp.blocker.data.MotivationItem
import com.focusapp.blocker.data.PendingChange
import com.focusapp.blocker.data.PreferencesManager
import com.focusapp.blocker.receiver.FocusDeviceAdminReceiver
import com.focusapp.blocker.receiver.PendingChangesReceiver
import com.focusapp.blocker.service.BlockingAccessibilityService
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// Kept for backward compatibility with MainActivity's authState check —
// always reports isAuthenticated = true (no server auth in local mode).
data class AuthState(
    val isAuthenticated: Boolean = true,
    val isLoading: Boolean = false,
    val userEmail: String? = null,
    val userName: String? = null,
    val errorMessage: String? = null
)

data class AppUiState(
    val blockedPackages: Set<String> = setOf(),
    val blockedKeywords: Set<String> = setOf(),
    val blockedWebsites: Set<String> = setOf(),
    val whitelistedPackages: Set<String> = setOf(),
    val whitelistedWebsites: Set<String> = setOf(),
    val whitelistedKeywords: Set<String> = setOf(),
    val pendingChanges: List<PendingChange> = emptyList(),
    val deletionProtectionEnabled: Boolean = false,
    val adultBlockingLevel: Int = 0,
    val lockEnabled: Boolean = false,
    val durationLocked: Boolean = false,
    val contentLocked: Boolean = false,
    val isDeviceOwner: Boolean = false,
    val settingsProtectionLevel: Int = 0,
    val motivationOnBlock: Boolean = false,
    val motivationOnSettings: Boolean = false,
    val hideAppIcon: Boolean = false,
    val blockYoutubeShorts: Boolean = false,
    val blockInstagramReels: Boolean = false,
    val motivation: MotivationConfig = MotivationConfig(),
    val downloadedVideos: Map<String, String> = emptyMap(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "AuthViewModel"
    private val preferencesManager = PreferencesManager(application)
    private val HIDDEN_ICON_NOTIF_ID = 8888
    private val HIDDEN_ICON_CHANNEL_ID = "hidden_icon_access"

    val termsAcceptedVersion = preferencesManager.termsAcceptedVersion

    fun acceptTerms(version: Int) {
        viewModelScope.launch { preferencesManager.acceptTerms(version) }
    }

    private val _authState = MutableStateFlow(AuthState(isAuthenticated = true))
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    private val _downloadingVideos = MutableStateFlow<Set<String>>(emptySet())
    val downloadingVideos: StateFlow<Set<String>> = _downloadingVideos.asStateFlow()

    init {
        loadConfig()
        loadDownloadedVideos()
        applyMaturePendingChanges()
        initAdultBlocklist()
    }

    private fun loadDownloadedVideos() {
        viewModelScope.launch {
            preferencesManager.downloadedVideos.collect { downloaded ->
                _uiState.value = _uiState.value.copy(downloadedVideos = downloaded)
            }
        }
    }

    private fun loadConfig() {
        viewModelScope.launch {
            val cached = preferencesManager.loadCachedConfig()
            val motivation = preferencesManager.loadMotivationConfig()
            val pendingChanges = preferencesManager.loadPendingChanges()
            val adultBlocking = preferencesManager.adultBlockingLevel.first()
            val lockEnabled = preferencesManager.lockEnabled.first()
            val durationLocked = preferencesManager.durationLocked.first()
            val contentLocked = preferencesManager.contentLocked.first()
            val settingsProtectionLevel = preferencesManager.settingsProtectionLevel.first()
            val motivationOnBlock = preferencesManager.motivationOnBlock.first()
            val motivationOnSettings = preferencesManager.motivationOnSettings.first()
            val hideAppIcon = preferencesManager.hideAppIcon.first()
            val blockYoutubeShorts = preferencesManager.blockYoutubeShorts.first()
            val blockInstagramReels = preferencesManager.blockInstagramReels.first()
            val ctx = getApplication<Application>()
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val isDeviceOwner = dpm.isDeviceOwnerApp(ctx.packageName)

            _uiState.value = _uiState.value.copy(
                blockedPackages = cached.blockedPackages,
                blockedKeywords = cached.blockedKeywords,
                blockedWebsites = cached.blockedWebsites,
                whitelistedPackages = ensureSelfWhitelisted(cached.whitelistedPackages),
                whitelistedWebsites = cached.whitelistedWebsites,
                whitelistedKeywords = cached.whitelistedKeywords,
                deletionProtectionEnabled = cached.deletionProtection,
                adultBlockingLevel = adultBlocking,
                lockEnabled = lockEnabled,
                durationLocked = durationLocked,
                contentLocked = contentLocked,
                isDeviceOwner = isDeviceOwner,
                settingsProtectionLevel = settingsProtectionLevel,
                motivationOnBlock = motivationOnBlock,
                motivationOnSettings = motivationOnSettings,
                hideAppIcon = hideAppIcon,
                blockYoutubeShorts = blockYoutubeShorts,
                blockInstagramReels = blockInstagramReels,
                motivation = motivation,
                pendingChanges = pendingChanges
            )
            syncMotivationToService(motivation)
            BlockingAccessibilityService.adultBlockingLevel = adultBlocking
            BlockingAccessibilityService.whitelistedKeywords = cached.whitelistedKeywords
            BlockingAccessibilityService.settingsProtectionLevel = settingsProtectionLevel
            BlockingAccessibilityService.motivationOnBlock = motivationOnBlock
            BlockingAccessibilityService.motivationOnSettings = motivationOnSettings
            applyIconVisibility(ctx, hideAppIcon)
            updateHiddenIconNotification(hideAppIcon)
            BlockingAccessibilityService.blockYoutubeShorts = blockYoutubeShorts
            BlockingAccessibilityService.blockInstagramReels = blockInstagramReels
            launch { try { refreshMissingTitles() } catch (e: Exception) { Log.e(TAG, "refreshMissingTitles failed", e) } }
        }
    }

    // Loads the cached adult domain list (if any) into the service immediately,
    // then downloads a fresh copy from GitHub in the background and caches it.
    private fun initAdultBlocklist() {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val cached = AdultBlockList.loadCached(ctx)
            if (cached.isNotEmpty()) {
                BlockingAccessibilityService.adultDomains = cached
            }
            // Always try to refresh from GitHub (silently — no UI feedback)
            val fresh = AdultBlockList.downloadAndCache(ctx)
            if (fresh.isNotEmpty()) {
                BlockingAccessibilityService.adultDomains = fresh
            } else if (cached.isEmpty()) {
                // Both download and cache failed — fall back to the hardcoded list
                BlockingAccessibilityService.adultDomains = AdultBlockList.FALLBACK_DOMAINS
            }
        }
    }

    private fun applyMaturePendingChanges() {
        viewModelScope.launch {
            val changes = preferencesManager.loadPendingChanges()
            val now = System.currentTimeMillis()
            val (mature, future) = changes.partition {
                Instant.parse(it.scheduledFor).toEpochMilli() <= now
            }
            if (mature.isEmpty()) return@launch
            mature.forEach { applyChange(it) }
            preferencesManager.savePendingChanges(future)
            loadConfig()
        }
    }

    private suspend fun applyChange(change: PendingChange) {
        when (change.type) {
            "remove_blocked_package" -> {
                val v = change.value ?: return
                preferencesManager.saveBlockedPackages(preferencesManager.blockedPackages.first() - v)
            }
            "remove_blocked_keyword" -> {
                val v = change.value ?: return
                preferencesManager.saveBlockedKeywords(preferencesManager.blockedKeywords.first() - v)
            }
            "remove_blocked_website" -> {
                val v = change.value ?: return
                preferencesManager.saveBlockedWebsites(preferencesManager.blockedWebsites.first() - v)
            }
            "add_whitelisted_package" -> {
                val v = change.value ?: return
                preferencesManager.saveWhitelistedPackages(preferencesManager.whitelistedPackages.first() + v)
            }
            "add_whitelisted_website" -> {
                val v = change.value ?: return
                preferencesManager.saveWhitelistedWebsites(preferencesManager.whitelistedWebsites.first() + v)
            }
            "disable_deletion_protection" -> {
                preferencesManager.saveDeletionProtection(false)
                val ctx = getApplication<Application>()
                val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val admin = ComponentName(ctx, FocusDeviceAdminReceiver::class.java)
                if (dpm.isAdminActive(admin)) dpm.removeActiveAdmin(admin)
            }
            "disable_adult_blocking" -> {
                preferencesManager.saveAdultBlockingLevel(0)
                BlockingAccessibilityService.adultBlockingLevel = 0
                _uiState.value = _uiState.value.copy(adultBlockingLevel = 0)
            }
            "lower_adult_blocking" -> {
                val level = change.value?.toIntOrNull() ?: return
                preferencesManager.saveAdultBlockingLevel(level)
                BlockingAccessibilityService.adultBlockingLevel = level
                _uiState.value = _uiState.value.copy(adultBlockingLevel = level)
            }
            "disable_lock" -> {
                preferencesManager.saveLockEnabled(false)
            }
            "unlock_duration" -> {
                preferencesManager.saveDurationLocked(false)
            }
            "unlock_content" -> {
                preferencesManager.saveContentLocked(false)
            }
            "disable_motivation_on_block" -> {
                preferencesManager.saveMotivationOnBlock(false)
                BlockingAccessibilityService.motivationOnBlock = false
            }
            "disable_motivation_on_settings" -> {
                preferencesManager.saveMotivationOnSettings(false)
                BlockingAccessibilityService.motivationOnSettings = false
            }
            "lower_settings_protection" -> {
                val level = change.value?.toIntOrNull() ?: 0
                preferencesManager.saveSettingsProtectionLevel(level)
                BlockingAccessibilityService.settingsProtectionLevel = level
            }
            "show_app_icon" -> {
                preferencesManager.saveHideAppIcon(false)
                val ctx = getApplication<Application>()
                applyIconVisibility(ctx, false)
                updateHiddenIconNotification(false)
            }
            "disable_youtube_shorts_block" -> {
                preferencesManager.saveBlockYoutubeShorts(false)
                BlockingAccessibilityService.blockYoutubeShorts = false
            }
            "disable_instagram_reels_block" -> {
                preferencesManager.saveBlockInstagramReels(false)
                BlockingAccessibilityService.blockInstagramReels = false
            }
        }
    }

    // ================== Public API ==================

    fun fetchConfig() { loadConfig() }

    fun fetchPendingChanges() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(pendingChanges = preferencesManager.loadPendingChanges())
        }
    }

    fun logout() { /* no-op in local mode */ }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(errorMessage = null, successMessage = null)
    }

    // ================== Configuration Management ==================

    fun addBlockedPackage(packageName: String) {
        if (packageName.isBlank()) return
        _uiState.value = _uiState.value.copy(blockedPackages = _uiState.value.blockedPackages + packageName)
        saveCurrentConfig()
    }

    fun removeBlockedPackage(packageName: String) {
        if (_uiState.value.lockEnabled) {
            queuePendingChange("remove_blocked_package", packageName)
        } else {
            _uiState.value = _uiState.value.copy(blockedPackages = _uiState.value.blockedPackages - packageName)
            saveCurrentConfig()
        }
    }

    fun addBlockedKeyword(keyword: String) {
        if (keyword.isBlank()) return
        _uiState.value = _uiState.value.copy(blockedKeywords = _uiState.value.blockedKeywords + keyword.lowercase())
        saveCurrentConfig()
    }

    fun removeBlockedKeyword(keyword: String) {
        if (_uiState.value.lockEnabled) {
            queuePendingChange("remove_blocked_keyword", keyword)
        } else {
            _uiState.value = _uiState.value.copy(blockedKeywords = _uiState.value.blockedKeywords - keyword)
            saveCurrentConfig()
        }
    }

    fun addBlockedWebsite(website: String) {
        if (website.isBlank()) return
        _uiState.value = _uiState.value.copy(blockedWebsites = _uiState.value.blockedWebsites + website.lowercase())
        saveCurrentConfig()
    }

    fun removeBlockedWebsite(website: String) {
        if (_uiState.value.lockEnabled) {
            queuePendingChange("remove_blocked_website", website)
        } else {
            _uiState.value = _uiState.value.copy(blockedWebsites = _uiState.value.blockedWebsites - website)
            saveCurrentConfig()
        }
    }

    fun removeWhitelistedPackage(packageName: String) {
        _uiState.value = _uiState.value.copy(whitelistedPackages = _uiState.value.whitelistedPackages - packageName)
        saveCurrentConfig()
    }

    fun addWhitelistedPackage(packageName: String) {
        if (packageName.isBlank()) return
        if (_uiState.value.lockEnabled) {
            queuePendingChange("add_whitelisted_package", packageName)
        } else {
            _uiState.value = _uiState.value.copy(whitelistedPackages = _uiState.value.whitelistedPackages + packageName)
            saveCurrentConfig()
        }
    }

    fun removeWhitelistedWebsite(website: String) {
        _uiState.value = _uiState.value.copy(whitelistedWebsites = _uiState.value.whitelistedWebsites - website)
        saveCurrentConfig()
    }

    fun addWhitelistedWebsite(website: String) {
        if (website.isBlank()) return
        if (_uiState.value.lockEnabled) {
            queuePendingChange("add_whitelisted_website", website.lowercase())
        } else {
            _uiState.value = _uiState.value.copy(whitelistedWebsites = _uiState.value.whitelistedWebsites + website.lowercase())
            saveCurrentConfig()
        }
    }

    fun addWhitelistedKeyword(keyword: String) {
        if (keyword.isBlank()) return
        val kw = keyword.lowercase()
        _uiState.value = _uiState.value.copy(whitelistedKeywords = _uiState.value.whitelistedKeywords + kw)
        BlockingAccessibilityService.whitelistedKeywords = _uiState.value.whitelistedKeywords
        saveCurrentConfig()
    }

    fun removeWhitelistedKeyword(keyword: String) {
        _uiState.value = _uiState.value.copy(whitelistedKeywords = _uiState.value.whitelistedKeywords - keyword)
        BlockingAccessibilityService.whitelistedKeywords = _uiState.value.whitelistedKeywords
        saveCurrentConfig()
    }

    // ================== Deletion Protection ==================

    fun onDeviceAdminEnabled() {
        viewModelScope.launch {
            preferencesManager.saveDeletionProtection(true)
            _uiState.value = _uiState.value.copy(
                deletionProtectionEnabled = true,
                successMessage = "Deletion protection enabled"
            )
        }
    }

    fun requestDisableDeletionProtection() {
        if (_uiState.value.lockEnabled) {
            queuePendingChange("disable_deletion_protection", null)
        } else {
            viewModelScope.launch {
                preferencesManager.saveDeletionProtection(false)
                val ctx = getApplication<Application>()
                val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val admin = ComponentName(ctx, FocusDeviceAdminReceiver::class.java)
                if (dpm.isAdminActive(admin)) dpm.removeActiveAdmin(admin)
                _uiState.value = _uiState.value.copy(deletionProtectionEnabled = false)
            }
        }
    }

    // ================== Adult Blocking ==================

    fun setAdultBlockingLevel(level: Int) {
        val current = _uiState.value.adultBlockingLevel
        if (level == current) return

        viewModelScope.launch {
            // Only one pending adult-blocking change may exist at a time. Clearing any
            // existing one first also means picking a new target replaces the old one
            // rather than stacking a second delayed downgrade behind it.
            clearPendingAdultChanges()

            // Every weakening waits 24 hours, not just turning it off entirely. Going from
            // restricted (sites + keywords) down to sites-only used to apply instantly,
            // which was a way around the delay: drop to level 1 now, and the keyword
            // blocking was gone immediately.
            if (level < current && _uiState.value.lockEnabled) {
                if (level == 0) queuePendingChange("disable_adult_blocking", null)
                else queuePendingChange("lower_adult_blocking", level.toString())
                return@launch
            }

            preferencesManager.saveAdultBlockingLevel(level)
            BlockingAccessibilityService.adultBlockingLevel = level
            _uiState.value = _uiState.value.copy(adultBlockingLevel = level)
        }
    }

    // Cancels queued adult-blocking downgrades (both kinds) and their alarms, in one pass,
    // so the caller can immediately queue a replacement without racing the DataStore write.
    private suspend fun clearPendingAdultChanges() {
        val existing = preferencesManager.loadPendingChanges()
        val doomed = existing.filter {
            it.type == "disable_adult_blocking" || it.type == "lower_adult_blocking"
        }
        if (doomed.isEmpty()) return
        val ctx = getApplication<Application>()
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        doomed.forEach { change ->
            val pi = PendingIntent.getBroadcast(
                ctx, change.id.hashCode(),
                Intent(ctx, PendingChangesReceiver::class.java).apply {
                    action = PendingChangesReceiver.ACTION_APPLY
                    putExtra(PendingChangesReceiver.EXTRA_CHANGE_ID, change.id)
                },
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pi != null) am.cancel(pi)
        }
        val remaining = existing - doomed.toSet()
        preferencesManager.savePendingChanges(remaining)
        _uiState.value = _uiState.value.copy(pendingChanges = remaining)
    }

    // ================== 24h Lock ==================

    fun enableLock() {
        viewModelScope.launch {
            preferencesManager.saveLockEnabled(true)
            _uiState.value = _uiState.value.copy(lockEnabled = true, successMessage = "24h lock enabled")
        }
    }

    fun requestDisableLock() {
        queuePendingChange("disable_lock", null)
    }

    fun lockDuration() {
        viewModelScope.launch {
            preferencesManager.saveDurationLocked(true)
            _uiState.value = _uiState.value.copy(durationLocked = true, successMessage = "Duration locked")
        }
    }

    fun unlockDuration() {
        if (_uiState.value.lockEnabled) {
            queuePendingChange("unlock_duration", null)
        } else {
            viewModelScope.launch {
                preferencesManager.saveDurationLocked(false)
                _uiState.value = _uiState.value.copy(durationLocked = false)
            }
        }
    }

    // ================== Behavior Settings ==================

    fun setSettingsProtectionLevel(level: Int) {
        viewModelScope.launch {
            val current = _uiState.value.settingsProtectionLevel
            if (_uiState.value.lockEnabled && level < current) {
                queuePendingChange("lower_settings_protection", level.toString())
                return@launch
            }
            preferencesManager.saveSettingsProtectionLevel(level)
            BlockingAccessibilityService.settingsProtectionLevel = level
            _uiState.value = _uiState.value.copy(settingsProtectionLevel = level)
        }
    }

    fun setMotivationOnBlock(enabled: Boolean) {
        viewModelScope.launch {
            if (!enabled && _uiState.value.lockEnabled) {
                queuePendingChange("disable_motivation_on_block", null)
                return@launch
            }
            preferencesManager.saveMotivationOnBlock(enabled)
            BlockingAccessibilityService.motivationOnBlock = enabled
            _uiState.value = _uiState.value.copy(motivationOnBlock = enabled)
        }
    }

    fun setMotivationOnSettings(enabled: Boolean) {
        viewModelScope.launch {
            if (!enabled && _uiState.value.lockEnabled) {
                queuePendingChange("disable_motivation_on_settings", null)
                return@launch
            }
            preferencesManager.saveMotivationOnSettings(enabled)
            BlockingAccessibilityService.motivationOnSettings = enabled
            _uiState.value = _uiState.value.copy(motivationOnSettings = enabled)
        }
    }

    fun setHideAppIcon(enabled: Boolean) {
        viewModelScope.launch {
            if (!enabled && _uiState.value.lockEnabled) {
                queuePendingChange("show_app_icon", null)
                return@launch
            }
            preferencesManager.saveHideAppIcon(enabled)
            val ctx = getApplication<Application>()
            applyIconVisibility(ctx, enabled)
            updateHiddenIconNotification(enabled)
            _uiState.value = _uiState.value.copy(hideAppIcon = enabled)
        }
    }

    fun setBlockYoutubeShorts(enabled: Boolean) {
        viewModelScope.launch {
            if (!enabled && _uiState.value.lockEnabled) {
                queuePendingChange("disable_youtube_shorts_block", null)
                return@launch
            }
            preferencesManager.saveBlockYoutubeShorts(enabled)
            BlockingAccessibilityService.blockYoutubeShorts = enabled
            _uiState.value = _uiState.value.copy(blockYoutubeShorts = enabled)
        }
    }

    fun setBlockInstagramReels(enabled: Boolean) {
        viewModelScope.launch {
            if (!enabled && _uiState.value.lockEnabled) {
                queuePendingChange("disable_instagram_reels_block", null)
                return@launch
            }
            preferencesManager.saveBlockInstagramReels(enabled)
            BlockingAccessibilityService.blockInstagramReels = enabled
            _uiState.value = _uiState.value.copy(blockInstagramReels = enabled)
        }
    }

    private fun updateHiddenIconNotification(show: Boolean) {
        val ctx = getApplication<Application>()
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!show) {
            nm.cancel(HIDDEN_ICON_NOTIF_ID)
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                HIDDEN_ICON_CHANNEL_ID,
                "App Access",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Tap to open LockIn while its icon is hidden"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
        val launchIntent = Intent(ctx, com.focusapp.blocker.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = PendingIntent.getActivity(
            ctx, HIDDEN_ICON_NOTIF_ID, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(ctx, HIDDEN_ICON_CHANNEL_ID)
            .setSmallIcon(com.focusapp.blocker.R.mipmap.ic_launcher)
            .setContentTitle("LockIn")
            .setContentText("Tap here to open the app — icon is hidden")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pi)
            .setShowWhen(false)
            .build()
        nm.notify(HIDDEN_ICON_NOTIF_ID, notification)
    }

    private fun applyIconVisibility(ctx: Context, hide: Boolean) {
        val state = if (hide) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        ctx.packageManager.setComponentEnabledSetting(
            ComponentName(ctx, "${ctx.packageName}.LauncherActivity"),
            state,
            android.content.pm.PackageManager.DONT_KILL_APP
        )
    }

    fun lockContent() {
        viewModelScope.launch {
            preferencesManager.saveContentLocked(true)
            _uiState.value = _uiState.value.copy(contentLocked = true, successMessage = "Content protection enabled")
        }
    }

    fun unlockContent() {
        if (_uiState.value.lockEnabled) {
            queuePendingChange("unlock_content", null)
        } else {
            viewModelScope.launch {
                preferencesManager.saveContentLocked(false)
                _uiState.value = _uiState.value.copy(contentLocked = false)
            }
        }
    }

    // ================== Pending Changes ==================

    private fun queuePendingChange(type: String, value: String?) {
        viewModelScope.launch {
            val changeId = UUID.randomUUID().toString()
            val now = Instant.now()
            val change = PendingChange(
                id = changeId,
                type = type,
                value = value,
                createdAt = now.toString(),
                scheduledFor = now.plus(24, ChronoUnit.HOURS).toString()
            )
            val updated = preferencesManager.loadPendingChanges() + change
            preferencesManager.savePendingChanges(updated)
            scheduleAlarm(change)
            _uiState.value = _uiState.value.copy(
                pendingChanges = updated,
                successMessage = "Change scheduled — takes effect in 24 hours"
            )
        }
    }

    fun cancelPendingChange(changeId: String) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                ctx, changeId.hashCode(),
                Intent(ctx, PendingChangesReceiver::class.java).apply {
                    action = PendingChangesReceiver.ACTION_APPLY
                    putExtra(PendingChangesReceiver.EXTRA_CHANGE_ID, changeId)
                },
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pi != null) am.cancel(pi)

            val updated = preferencesManager.loadPendingChanges().filter { it.id != changeId }
            preferencesManager.savePendingChanges(updated)
            _uiState.value = _uiState.value.copy(pendingChanges = updated, successMessage = "Scheduled change cancelled")
        }
    }

    private fun scheduleAlarm(change: PendingChange) {
        val ctx = getApplication<Application>()
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            ctx, change.id.hashCode(),
            Intent(ctx, PendingChangesReceiver::class.java).apply {
                action = PendingChangesReceiver.ACTION_APPLY
                putExtra(PendingChangesReceiver.EXTRA_CHANGE_ID, change.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = Instant.parse(change.scheduledFor).toEpochMilli()
        // No SCHEDULE_EXACT_ALARM: Play restricts exact alarms, and this timer does not need
        // them. canScheduleExactAlarms() is false without the permission, so this takes the
        // inexact path, which fires within minutes of the target — irrelevant on a 24-hour
        // delay. It also fails in the safe direction: a late alarm means protection stays on
        // slightly longer, never that it lifts early. And applyMaturePendingChanges() applies
        // anything overdue whenever the app opens, so a missed alarm cannot strand a change.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun saveCurrentConfig() {
        viewModelScope.launch {
            val s = _uiState.value
            preferencesManager.saveBlockedPackages(s.blockedPackages)
            preferencesManager.saveBlockedKeywords(s.blockedKeywords)
            preferencesManager.saveBlockedWebsites(s.blockedWebsites)
            preferencesManager.saveWhitelistedPackages(ensureSelfWhitelisted(s.whitelistedPackages))
            preferencesManager.saveWhitelistedWebsites(s.whitelistedWebsites)
            preferencesManager.saveWhitelistedKeywords(s.whitelistedKeywords)
            preferencesManager.saveDeletionProtection(s.deletionProtectionEnabled)
        }
    }

    private fun ensureSelfWhitelisted(packages: Set<String>): Set<String> =
        packages + getApplication<Application>().packageName

    // ================== Motivation ==================

    fun addMotivationVideo(url: String, label: String?) {
        viewModelScope.launch {
            val current = preferencesManager.loadMotivationConfig()
            val updated = current.copy(videos = current.videos + MotivationItem(url, label))
            preferencesManager.saveMotivationConfig(updated)
            preferencesManager.saveMotivationVideos(updated.videos.map { it.url }.toSet())
            _uiState.value = _uiState.value.copy(motivation = updated, successMessage = "Video added")
            syncMotivationToService(updated)
            if (label == null) {
                val title = fetchVideoTitle(url)
                if (title != null) {
                    val refreshed = preferencesManager.loadMotivationConfig()
                    val idx = refreshed.videos.indexOfLast { it.url == url && it.label == null }
                    if (idx >= 0) {
                        val withTitle = refreshed.copy(videos = refreshed.videos.toMutableList().also { it[idx] = it[idx].copy(label = title) })
                        preferencesManager.saveMotivationConfig(withTitle)
                        _uiState.value = _uiState.value.copy(motivation = withTitle)
                        syncMotivationToService(withTitle)
                    }
                }
            }
        }
    }

    fun removeMotivationVideo(index: Int) {
        viewModelScope.launch {
            val current = preferencesManager.loadMotivationConfig()
            if (index < 0 || index >= current.videos.size) return@launch
            val updated = current.copy(videos = current.videos.toMutableList().also { it.removeAt(index) })
            preferencesManager.saveMotivationConfig(updated)
            preferencesManager.saveMotivationVideos(updated.videos.map { it.url }.toSet())
            _uiState.value = _uiState.value.copy(motivation = updated)
            syncMotivationToService(updated)
        }
    }

    fun addMotivationChannel(url: String, label: String?) {
        viewModelScope.launch {
            val current = preferencesManager.loadMotivationConfig()
            val updated = current.copy(channels = current.channels + MotivationItem(url, label))
            preferencesManager.saveMotivationConfig(updated)
            preferencesManager.saveMotivationChannels(updated.channels.map { it.url }.toSet())
            _uiState.value = _uiState.value.copy(motivation = updated, successMessage = "Channel added")
            syncMotivationToService(updated)
            if (label == null) {
                val title = fetchVideoTitle(url)
                if (title != null) {
                    val refreshed = preferencesManager.loadMotivationConfig()
                    val idx = refreshed.channels.indexOfLast { it.url == url && it.label == null }
                    if (idx >= 0) {
                        val withTitle = refreshed.copy(channels = refreshed.channels.toMutableList().also { it[idx] = it[idx].copy(label = title) })
                        preferencesManager.saveMotivationConfig(withTitle)
                        _uiState.value = _uiState.value.copy(motivation = withTitle)
                        syncMotivationToService(withTitle)
                    }
                }
            }
        }
    }

    fun removeMotivationChannel(index: Int) {
        viewModelScope.launch {
            val current = preferencesManager.loadMotivationConfig()
            if (index < 0 || index >= current.channels.size) return@launch
            val updated = current.copy(channels = current.channels.toMutableList().also { it.removeAt(index) })
            preferencesManager.saveMotivationConfig(updated)
            preferencesManager.saveMotivationChannels(updated.channels.map { it.url }.toSet())
            _uiState.value = _uiState.value.copy(motivation = updated)
            syncMotivationToService(updated)
        }
    }

    fun updateMotivationDuration(seconds: Int) {
        viewModelScope.launch {
            val current = preferencesManager.loadMotivationConfig()
            val updated = current.copy(duration = seconds)
            preferencesManager.saveMotivationConfig(updated)
            _uiState.value = _uiState.value.copy(motivation = updated)
            syncMotivationToService(updated)
        }
    }

    fun addMotivationPhrase(phrase: String) {
        if (phrase.isBlank()) return
        viewModelScope.launch {
            val current = preferencesManager.loadMotivationConfig()
            val updated = current.copy(phrases = current.phrases + phrase.trim())
            preferencesManager.saveMotivationConfig(updated)
            _uiState.value = _uiState.value.copy(motivation = updated, successMessage = "Phrase added")
            syncMotivationToService(updated)
        }
    }

    fun removeMotivationPhrase(index: Int) {
        viewModelScope.launch {
            val current = preferencesManager.loadMotivationConfig()
            if (index < 0 || index >= current.phrases.size) return@launch
            val updated = current.copy(phrases = current.phrases.toMutableList().also { it.removeAt(index) })
            preferencesManager.saveMotivationConfig(updated)
            _uiState.value = _uiState.value.copy(motivation = updated)
            syncMotivationToService(updated)
        }
    }

    fun addGalleryVideo(context: Context, uri: android.net.Uri, label: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val current = preferencesManager.loadMotivationConfig()
                val filename = "gallery_${System.currentTimeMillis()}.mp4"
                val videosDir = File(context.getExternalFilesDir(null), "motivation_videos").also { it.mkdirs() }
                val destFile = File(videosDir, filename)

                context.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }

                val autoLabel = label ?: "Gallery video ${current.galleryVideos.size + 1}"
                val updated = current.copy(
                    galleryVideos = current.galleryVideos + MotivationItem("gallery://$filename", autoLabel)
                )
                preferencesManager.saveMotivationConfig(updated)
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(motivation = updated, successMessage = "Gallery video added")
                    syncMotivationToService(updated)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gallery import failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(errorMessage = "Failed to import video: ${e.message}")
                }
            }
        }
    }

    fun removeGalleryVideo(index: Int) {
        viewModelScope.launch {
            val current = preferencesManager.loadMotivationConfig()
            if (index < 0 || index >= current.galleryVideos.size) return@launch
            val item = current.galleryVideos[index]
            val filename = item.url.removePrefix("gallery://")
            withContext(Dispatchers.IO) {
                try {
                    val ctx = getApplication<Application>()
                    File(ctx.getExternalFilesDir(null), "motivation_videos/$filename").delete()
                } catch (e: Exception) { }
            }
            val updated = current.copy(galleryVideos = current.galleryVideos.toMutableList().also { it.removeAt(index) })
            preferencesManager.saveMotivationConfig(updated)
            _uiState.value = _uiState.value.copy(motivation = updated)
            syncMotivationToService(updated)
        }
    }

    private fun syncMotivationToService(motivation: MotivationConfig) {
        BlockingAccessibilityService.motivationVideos = motivation.videos.map { it.url }
        BlockingAccessibilityService.motivationChannels = motivation.channels.map { it.url }
        BlockingAccessibilityService.motivationGalleryVideos = motivation.galleryVideos.map { it.url }
        BlockingAccessibilityService.motivationPhrases = motivation.phrases
        BlockingAccessibilityService.motivationDuration = motivation.duration
    }

    // ================== Channel URL Resolution (local) ==================

    suspend fun resolveChannelUrl(channelUrl: String): String {
        if (extractYouTubeVideoId(channelUrl) != null) return channelUrl
        if (!channelUrl.contains("youtube.com") && !channelUrl.contains("youtu.be")) return channelUrl

        return withTimeoutOrNull(8_000) {
            resolveYouTubeChannelToVideo(channelUrl)
        } ?: channelUrl
    }

    suspend fun resolveInstagramChannelUrl(profileUrl: String): String {
        if (Regex("""instagram\.com/(?:reel|p|tv)/""").containsMatchIn(profileUrl)) return profileUrl

        return withContext(Dispatchers.IO) {
            try {
                val username = Regex("""instagram\.com/([A-Za-z0-9._]+)""")
                    .find(profileUrl)?.groupValues?.get(1) ?: return@withContext profileUrl

                // Strategy 1: unofficial JSON endpoint (still works on many Instagram accounts)
                try {
                    val json = httpGet(
                        "https://www.instagram.com/$username/?__a=1&__d=dis",
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                            "X-IG-App-ID" to "936619743392459",
                            "Accept" to "application/json, text/plain, */*",
                            "X-Requested-With" to "XMLHttpRequest"
                        )
                    )
                    val codes = extractInstagramShortcodes(json)
                    if (codes.isNotEmpty()) return@withContext "https://www.instagram.com/reel/${codes.random()}/"
                } catch (e: Exception) { Log.w(TAG, "Instagram JSON API: ${e.message}") }

                // Strategy 2: try multiple crawler UAs on the profile and reels pages
                val userAgents = listOf(
                    "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uagent.php)",
                    "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
                    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
                for (ua in userAgents) {
                    for (path in listOf("reels/", "")) {
                        val html = try {
                            httpGet(
                                "https://www.instagram.com/$username/$path",
                                headers = mapOf(
                                    "User-Agent" to ua,
                                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                                    "Accept-Language" to "en-US,en;q=0.5"
                                )
                            )
                        } catch (e: Exception) { null } ?: continue

                        val codes = extractInstagramShortcodes(html)
                        if (codes.isNotEmpty()) return@withContext "https://www.instagram.com/reel/${codes.random()}/"
                    }
                }

                // All server-side strategies failed — fall back to the reels tab so the
                // WebView lands on the most video-dense page instead of the general profile
                "https://www.instagram.com/$username/reels/"
            } catch (e: Exception) {
                Log.e(TAG, "Instagram channel resolve error: ${e.message}")
                profileUrl
            }
        }
    }

    private fun extractInstagramShortcodes(text: String): List<String> =
        listOf(
            Regex(""""shortcode"\s*:\s*"([A-Za-z0-9_-]{8,15})""""),
            Regex(""""code"\s*:\s*"([A-Za-z0-9_-]{8,15})""""),
            Regex("""href=["']/(?:reel|p|tv)/([A-Za-z0-9_-]{8,})/"""),
            Regex("""instagram\.com/(?:reel|p|tv)/([A-Za-z0-9_-]{8,})""")
        ).flatMap { it.findAll(text).map { m -> m.groupValues[1] } }
            .distinct()
            .filter { it.length in 8..15 }
            .toList()

    private suspend fun resolveYouTubeChannelToVideo(channelUrl: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val pageHtml = httpGet(channelUrl)
                val channelId = Regex("\"externalId\":\"(UC[^\"]+)\"").find(pageHtml)?.groupValues?.get(1)
                    ?: Regex("\"channelId\":\"(UC[^\"]+)\"").find(pageHtml)?.groupValues?.get(1)
                    ?: return@withContext channelUrl

                val rssFeed = httpGet("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId")
                val videoIds = Regex("<yt:videoId>([a-zA-Z0-9_-]{11})</yt:videoId>")
                    .findAll(rssFeed).map { it.groupValues[1] }.toList()

                if (videoIds.isNotEmpty()) "https://www.youtube.com/watch?v=${videoIds.random()}"
                else channelUrl
            } catch (e: Exception) {
                Log.e(TAG, "Channel resolve error: ${e.message}")
                channelUrl
            }
        }
    }

    // ================== Video Download ==================

    fun downloadVideo(context: Context, videoUrl: String) {
        if (_downloadingVideos.value.contains(videoUrl)) return
        viewModelScope.launch(Dispatchers.IO) {
            _downloadingVideos.value = _downloadingVideos.value + videoUrl
            try {
                val downloadUrl = when {
                    videoUrl.contains("youtube.com") || videoUrl.contains("youtu.be") -> {
                        val videoId = extractYouTubeVideoId(videoUrl)
                            ?: throw Exception("Invalid YouTube URL")
                        resolveYouTubeDownloadUrl(videoId)
                            ?: throw Exception("Could not retrieve YouTube download URL — try a different video")
                    }
                    videoUrl.contains("instagram.com") -> {
                        resolveInstagramDownloadUrl(videoUrl)
                            ?: throw Exception("Could not retrieve Instagram video — make sure the post is public")
                    }
                    else -> throw Exception("Download is supported for YouTube and Instagram videos only")
                }

                val fileName = "${Math.abs(videoUrl.hashCode())}.mp4"
                val videosDir = File(context.getExternalFilesDir(null), "motivation_videos").also { it.mkdirs() }
                val destFile = File(videosDir, fileName)

                val conn = java.net.URL(downloadUrl).openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                conn.setRequestProperty("Referer", "https://www.instagram.com/")
                conn.connectTimeout = 30_000
                conn.readTimeout = 120_000

                val buffer = ByteArray(8192)
                conn.inputStream.use { input ->
                    destFile.outputStream().use { output ->
                        var bytes = input.read(buffer)
                        while (bytes >= 0) {
                            output.write(buffer, 0, bytes)
                            bytes = input.read(buffer)
                        }
                    }
                }

                preferencesManager.saveDownloadedVideo(videoUrl, destFile.absolutePath)
                _uiState.value = _uiState.value.copy(successMessage = "Video downloaded for offline use")
            } catch (e: Exception) {
                Log.e(TAG, "Download error: ${e.message}")
                _uiState.value = _uiState.value.copy(errorMessage = "Download failed: ${e.message}")
            } finally {
                _downloadingVideos.value = _downloadingVideos.value - videoUrl
            }
        }
    }

    private suspend fun resolveInstagramDownloadUrl(postUrl: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val shortcode = Regex("instagram\\.com/(?:p|reel|tv)/([A-Za-z0-9_-]+)")
                    .find(postUrl)?.groupValues?.get(1) ?: return@withContext null

                // Approach 1: Facebook crawler UA — Instagram server-renders og:video meta tags
                // for Facebook link previews, so public posts return video URLs without auth.
                val crawlerHtml = try {
                    httpGet(
                        "https://www.instagram.com/p/$shortcode/",
                        headers = mapOf(
                            "User-Agent" to "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uagent.php)",
                            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                            "Accept-Language" to "en-US,en;q=0.5"
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Instagram crawler fetch failed: ${e.message}")
                    ""
                }

                if (crawlerHtml.isNotEmpty()) {
                    // og:video attribute order varies — match both orderings
                    val ogVideo =
                        Regex("<meta[^>]+property=[\"']og:video[\"'][^>]+content=[\"']([^\"']+)[\"']")
                            .find(crawlerHtml)?.groupValues?.get(1)?.unescape()
                        ?: Regex("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']og:video[\"']")
                            .find(crawlerHtml)?.groupValues?.get(1)?.unescape()
                    if (ogVideo != null) return@withContext ogVideo
                }

                // Approach 2: Embed page with realistic desktop browser fingerprint
                val embedHtml = try {
                    httpGet(
                        "https://www.instagram.com/p/$shortcode/embed/",
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                            "Accept-Language" to "en-US,en;q=0.5",
                            "Sec-Fetch-Dest" to "document",
                            "Sec-Fetch-Mode" to "navigate",
                            "Sec-Fetch-Site" to "none"
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Instagram embed fetch failed: ${e.message}")
                    ""
                }

                if (embedHtml.isNotEmpty()) {
                    Regex("\"videoUrl\":\"([^\"]+)\"").find(embedHtml)?.groupValues?.get(1)?.unescape()
                        ?: Regex("\"video_url\":\"([^\"]+)\"").find(embedHtml)?.groupValues?.get(1)?.unescape()
                        ?: Regex("\"playable_url\":\"([^\"]+)\"").find(embedHtml)?.groupValues?.get(1)?.unescape()
                        ?: Regex("\"contentUrl\":\\s*\"([^\"]+)\"").find(embedHtml)?.groupValues?.get(1)?.unescape()
                        ?: Regex("<link[^>]+rel=[\"']preload[\"'][^>]+as=[\"']video[\"'][^>]+href=[\"']([^\"']+)[\"']").find(embedHtml)?.groupValues?.get(1)
                        ?: Regex("<video[^>]+src=[\"']([^\"']+)[\"']").find(embedHtml)?.groupValues?.get(1)
                } else null
            } catch (e: Exception) {
                Log.e(TAG, "Instagram download error: ${e.message}")
                null
            }
        }
    }

    // Decodes JSON string escapes and HTML entities (numeric hex/decimal + common named ones)
    private fun String.unescape(): String = this
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace(Regex("&#x([0-9a-fA-F]+);", RegexOption.IGNORE_CASE)) { mr ->
            mr.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: mr.value
        }
        .replace(Regex("&#([0-9]+);")) { mr ->
            mr.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: mr.value
        }
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")

    private fun extractYouTubeVideoId(url: String): String? {
        val patterns = listOf(
            Regex("(?:youtube\\.com/watch\\?v=|youtu\\.be/)([a-zA-Z0-9_-]{11})"),
            Regex("youtube\\.com/shorts/([a-zA-Z0-9_-]{11})")
        )
        for (p in patterns) p.find(url)?.groupValues?.get(1)?.let { return it }
        return null
    }

    private suspend fun resolveYouTubeDownloadUrl(videoId: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val body = """{"videoId":"$videoId","context":{"client":{"clientName":"ANDROID","clientVersion":"19.09.37","androidSdkVersion":30}}}"""
                val conn = java.net.URL("https://www.youtube.com/youtubei/v1/player").openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("User-Agent", "com.google.android.youtube/19.09.37(Linux; U; Android 11) gzip")
                conn.doOutput = true
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.outputStream.use { it.write(body.toByteArray()) }

                val json = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
                val formats = json.optJSONObject("streamingData")?.optJSONArray("formats")
                    ?: return@withContext null

                var bestUrl: String? = null
                var bestHeight = -1
                for (i in 0 until formats.length()) {
                    val fmt = formats.getJSONObject(i)
                    val h = fmt.optInt("height", 0)
                    val u = fmt.optString("url", "")
                    if (u.isNotBlank() && h > bestHeight) { bestUrl = u; bestHeight = h }
                }
                bestUrl
            } catch (e: Exception) {
                Log.e(TAG, "InnerTube error: ${e.message}")
                null
            }
        }
    }


    private suspend fun fetchVideoTitle(url: String): String? = withContext(Dispatchers.IO) {
        try {
            when {
                url.contains("youtube.com") || url.contains("youtu.be") -> {
                    val encoded = java.net.URLEncoder.encode(url, "UTF-8")
                    val json = org.json.JSONObject(httpGet("https://www.youtube.com/oembed?url=$encoded&format=json"))
                    json.optString("title").takeIf { it.isNotBlank() }
                }
                url.contains("instagram.com") -> {
                    // Use Facebook crawler UA — Instagram serves og:title for link previews without auth
                    val html = httpGet(
                        url,
                        headers = mapOf(
                            "User-Agent" to "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uagent.php)",
                            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                        )
                    )
                    val title = (
                        Regex("<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                            .find(html)?.groupValues?.get(1)
                        ?: Regex("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']og:title[\"']", RegexOption.IGNORE_CASE)
                            .find(html)?.groupValues?.get(1)
                    )?.trim()?.unescape()
                    title?.takeIf { it.lowercase() !in GENERIC_INSTAGRAM_TITLES }
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Title fetch failed for $url: ${e.message}")
            null
        }
    }

    private fun needsTitleFetch(item: com.focusapp.blocker.data.MotivationItem): Boolean =
        item.label == null ||
        item.label.lowercase() in GENERIC_INSTAGRAM_TITLES ||
        item.label.contains("&#")

    private suspend fun refreshMissingTitles() {
        val config = preferencesManager.loadMotivationConfig()
        var changed = false
        val updatedVideos = config.videos.map { item ->
            if (needsTitleFetch(item)) {
                val title = fetchVideoTitle(item.url)
                if (title != null) { changed = true; item.copy(label = title) } else item
            } else item
        }
        val updatedChannels = config.channels.map { item ->
            if (needsTitleFetch(item)) {
                val title = fetchVideoTitle(item.url)
                if (title != null) { changed = true; item.copy(label = title) } else item
            } else item
        }
        if (changed) {
            val updated = config.copy(videos = updatedVideos, channels = updatedChannels)
            preferencesManager.saveMotivationConfig(updated)
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(motivation = updated)
                syncMotivationToService(updated)
            }
        }
    }

    private fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.instanceFollowRedirects = true
        val stream = conn.inputStream
        val charset = conn.contentType
            ?.let { Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.trim() }
            ?.let { try { java.nio.charset.Charset.forName(it) } catch (_: Exception) { null } }
            ?: Charsets.UTF_8
        return stream.bufferedReader(charset).readText()
    }

    companion object {
        private val GENERIC_INSTAGRAM_TITLES = setOf("instagram", "login • instagram", "login", "")
    }
}
