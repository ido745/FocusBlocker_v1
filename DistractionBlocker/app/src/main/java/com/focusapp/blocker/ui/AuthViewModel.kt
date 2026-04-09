package com.focusapp.blocker.ui

import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.focusapp.blocker.data.AuthManager
import com.focusapp.blocker.data.AuthenticatedRepository
import com.focusapp.blocker.data.Blocklists
import com.focusapp.blocker.data.Device
import com.focusapp.blocker.data.MotivationConfig
import com.focusapp.blocker.data.MotivationItem
import com.focusapp.blocker.data.PendingChange
import com.focusapp.blocker.data.PreferencesManager
import com.focusapp.blocker.data.Session
import com.focusapp.blocker.data.Whitelists
import com.focusapp.blocker.receiver.FocusDeviceAdminReceiver
import com.focusapp.blocker.service.BlockingAccessibilityService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class AuthState(
    val isAuthenticated: Boolean = false,
    val isLoading: Boolean = false,
    val userEmail: String? = null,
    val userName: String? = null,
    val userPicture: String? = null,
    val errorMessage: String? = null
)

data class AppUiState(
    val serverUrl: String = "https://focus-blocker-backend.onrender.com",
    val session: Session? = null,
    val devices: List<Device> = emptyList(),
    val currentDeviceId: String? = null,
    val blockedPackages: Set<String> = setOf(),
    val blockedKeywords: Set<String> = setOf(),
    val blockedWebsites: Set<String> = setOf(),
    val whitelistedPackages: Set<String> = setOf(),
    val whitelistedWebsites: Set<String> = setOf(),
    val pendingChanges: List<PendingChange> = emptyList(),
    val deletionProtectionEnabled: Boolean = false,
    val motivation: MotivationConfig = MotivationConfig(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val preferencesManager = PreferencesManager(application)
    private val authManager = AuthManager(application)
    private var repository: AuthenticatedRepository? = null

    private val _authState = MutableStateFlow(AuthState())
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    /** Emits a video URL whenever the accessibility guard fires and we should auto-play motivation. */
    private val _motivationAutoPlay = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val motivationAutoPlay: SharedFlow<String> = _motivationAutoPlay.asSharedFlow()

    init {
        loadPreferences()
        loadCachedConfig()
        checkAuthStatus()
    }

    private fun loadCachedConfig() {
        viewModelScope.launch {
            val cached = preferencesManager.loadCachedConfig()
            // Only apply cache if it contains actual data (not the empty defaults)
            if (cached.blockedPackages.isNotEmpty() || cached.whitelistedPackages.isNotEmpty()) {
                _uiState.value = _uiState.value.copy(
                    blockedPackages = cached.blockedPackages,
                    blockedKeywords = cached.blockedKeywords,
                    blockedWebsites = cached.blockedWebsites,
                    whitelistedPackages = cached.whitelistedPackages,
                    whitelistedWebsites = cached.whitelistedWebsites,
                    deletionProtectionEnabled = cached.deletionProtection
                )
            }
        }
    }

    private fun loadPreferences() {
        viewModelScope.launch {
            preferencesManager.serverUrl.collect { url ->
                _uiState.value = _uiState.value.copy(serverUrl = url)
                repository = AuthenticatedRepository(getApplication(), url)
                if (_authState.value.isAuthenticated) {
                    fetchData()
                }
            }
        }
    }

    private fun checkAuthStatus() {
        viewModelScope.launch {
            val token = authManager.authToken.first()
            val email = authManager.userEmail.first()
            val name = authManager.userName.first()
            val deviceId = authManager.getDeviceId()

            _authState.value = _authState.value.copy(
                isAuthenticated = !token.isNullOrBlank(),
                userEmail = email,
                userName = name
            )
            _uiState.value = _uiState.value.copy(currentDeviceId = deviceId)

            if (!token.isNullOrBlank()) {
                fetchData()
            }
        }
    }

    // ================== Authentication ==================

    fun register(email: String, password: String, name: String) {
        viewModelScope.launch {
            _authState.value = _authState.value.copy(isLoading = true, errorMessage = null)
            repository?.register(email, password, name)?.onSuccess { user ->
                _authState.value = _authState.value.copy(
                    isAuthenticated = true,
                    userEmail = user.email,
                    userName = user.name,
                    isLoading = false
                )
                fetchData()
            }?.onFailure { error ->
                _authState.value = _authState.value.copy(
                    isLoading = false,
                    errorMessage = "Registration failed: ${error.message}"
                )
            }
        }
    }

    fun login(email: String, password: String) {
        viewModelScope.launch {
            _authState.value = _authState.value.copy(isLoading = true, errorMessage = null)
            repository?.login(email, password)?.onSuccess { user ->
                _authState.value = _authState.value.copy(
                    isAuthenticated = true,
                    userEmail = user.email,
                    userName = user.name,
                    isLoading = false
                )
                fetchData()
            }?.onFailure { error ->
                _authState.value = _authState.value.copy(
                    isLoading = false,
                    errorMessage = "Login failed: ${error.message}"
                )
            }
        }
    }

    fun signInWithGoogle(idToken: String) {
        viewModelScope.launch {
            _authState.value = _authState.value.copy(isLoading = true, errorMessage = null)
            repository?.googleAuth(idToken)?.onSuccess { user ->
                _authState.value = _authState.value.copy(
                    isAuthenticated = true,
                    userEmail = user.email,
                    userName = user.name,
                    userPicture = user.picture,
                    isLoading = false
                )
                fetchData()
            }?.onFailure { error ->
                _authState.value = _authState.value.copy(
                    isLoading = false,
                    errorMessage = "Google sign-in failed: ${error.message}"
                )
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            repository?.logout()
            _authState.value = AuthState(isAuthenticated = false)
            _uiState.value = AppUiState(serverUrl = _uiState.value.serverUrl)
        }
    }

    // ================== Data Fetching ==================

    private fun fetchData() {
        fetchConfig()
        fetchDevices()
        fetchPendingChanges()
    }

    fun fetchConfig() {
        viewModelScope.launch {
            // Don't clear errorMessage here — it may have been set by a concurrent operation
            // (e.g. addMotivationVideo) and we don't want to swallow it.
            _uiState.value = _uiState.value.copy(isLoading = true)
            repository?.getConfig()?.onSuccess { config ->
                val blocked = config.blocklists.packages.orEmpty().toSet()
                val keywords = config.blocklists.keywords.orEmpty().toSet()
                val websites = config.blocklists.websites.orEmpty().toSet()
                val whitelistPkgs = config.whitelists.packages.orEmpty().toSet()
                val whitelistSites = config.whitelists.websites.orEmpty().toSet()
                _uiState.value = _uiState.value.copy(
                    blockedPackages = blocked,
                    blockedKeywords = keywords,
                    blockedWebsites = websites,
                    whitelistedPackages = whitelistPkgs,
                    whitelistedWebsites = whitelistSites,
                    deletionProtectionEnabled = config.deletionProtection,
                    motivation = config.motivation,
                    isLoading = false
                )
                // Sync motivation to accessibility service companion
                BlockingAccessibilityService.motivationVideos = config.motivation.videos.map { it.url }
                BlockingAccessibilityService.motivationChannels = config.motivation.channels.map { it.url }
                BlockingAccessibilityService.motivationDuration = config.motivation.duration
                // Persist to local cache so the app survives a server cold-start (502)
                preferencesManager.saveBlockedPackages(blocked)
                preferencesManager.saveBlockedKeywords(keywords)
                preferencesManager.saveBlockedWebsites(websites)
                preferencesManager.saveWhitelistedPackages(whitelistPkgs)
                preferencesManager.saveWhitelistedWebsites(whitelistSites)
                preferencesManager.saveDeletionProtection(config.deletionProtection)
            }?.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Failed to load config: ${error.message}"
                )
            }
        }
    }

    fun fetchDevices() {
        viewModelScope.launch {
            repository?.getDevices()?.onSuccess { devices ->
                _uiState.value = _uiState.value.copy(devices = devices)
            }?.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to load devices: ${error.message}"
                )
            }
        }
    }

    fun fetchPendingChanges() {
        viewModelScope.launch {
            repository?.getPendingChanges()?.onSuccess { changes ->
                _uiState.value = _uiState.value.copy(pendingChanges = changes)
            }?.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to load pending changes: ${error.message}"
                )
            }
        }
    }

    // ================== Server URL ==================

    fun updateServerUrl(url: String) {
        viewModelScope.launch {
            preferencesManager.saveServerUrl(url)
            _uiState.value = _uiState.value.copy(serverUrl = url)
            repository = AuthenticatedRepository(getApplication(), url)
        }
    }

    // ================== Configuration Management ==================
    // Adding to blocklists (tightening) = immediate
    // Removing from blocklists or adding to whitelist (relaxing) = 24-hour delay

    fun addBlockedPackage(packageName: String) {
        if (packageName.isBlank()) return
        val updated = _uiState.value.blockedPackages + packageName
        _uiState.value = _uiState.value.copy(blockedPackages = updated)
        syncConfig()
    }

    /** Removing a blocked app is a constraint relaxation — 24-hour delay. */
    fun removeBlockedPackage(packageName: String) {
        queuePendingChange("remove_blocked_package", packageName)
    }

    fun addBlockedKeyword(keyword: String) {
        if (keyword.isBlank()) return
        val updated = _uiState.value.blockedKeywords + keyword.lowercase()
        _uiState.value = _uiState.value.copy(blockedKeywords = updated)
        syncConfig()
    }

    /** Removing a blocked keyword is a constraint relaxation — 24-hour delay. */
    fun removeBlockedKeyword(keyword: String) {
        queuePendingChange("remove_blocked_keyword", keyword)
    }

    fun addBlockedWebsite(website: String) {
        if (website.isBlank()) return
        val updated = _uiState.value.blockedWebsites + website.lowercase()
        _uiState.value = _uiState.value.copy(blockedWebsites = updated)
        syncConfig()
    }

    /** Removing a blocked website is a constraint relaxation — 24-hour delay. */
    fun removeBlockedWebsite(website: String) {
        queuePendingChange("remove_blocked_website", website)
    }

    /** Removing from whitelist (tightening) = immediate. */
    fun removeWhitelistedPackage(packageName: String) {
        val updated = _uiState.value.whitelistedPackages - packageName
        _uiState.value = _uiState.value.copy(whitelistedPackages = updated)
        syncConfig()
    }

    /** Adding to whitelist (relaxing — bypasses a block) = 24-hour delay. */
    fun addWhitelistedPackage(packageName: String) {
        if (packageName.isBlank()) return
        queuePendingChange("add_whitelisted_package", packageName)
    }

    /** Removing from whitelist (tightening) = immediate. */
    fun removeWhitelistedWebsite(website: String) {
        val updated = _uiState.value.whitelistedWebsites - website
        _uiState.value = _uiState.value.copy(whitelistedWebsites = updated)
        syncConfig()
    }

    /** Adding to whitelist (relaxing) = 24-hour delay. */
    fun addWhitelistedWebsite(website: String) {
        if (website.isBlank()) return
        queuePendingChange("add_whitelisted_website", website.lowercase())
    }

    // ================== Deletion Protection ==================

    /** Enable deletion protection immediately (tightening constraint). */
    fun enableDeletionProtection(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(context, FocusDeviceAdminReceiver::class.java)
        return if (dpm.isAdminActive(adminComponent)) {
            // Already admin — just sync the flag to server
            viewModelScope.launch {
                repository?.updateConfig(deletionProtectionEnabled = true)
                _uiState.value = _uiState.value.copy(deletionProtectionEnabled = true)
            }
            true
        } else {
            // Caller must launch the admin activation intent; returns false to indicate
            // the intent is needed. The flag will be set after the intent resolves.
            false
        }
    }

    /** Called from MainActivity after device admin is successfully activated. */
    fun onDeviceAdminEnabled() {
        viewModelScope.launch {
            repository?.updateConfig(deletionProtectionEnabled = true)
            _uiState.value = _uiState.value.copy(deletionProtectionEnabled = true)
            _uiState.value = _uiState.value.copy(successMessage = "Deletion protection enabled")
        }
    }

    /**
     * Disable deletion protection — queues a 24-hour pending change.
     * The device admin will be deactivated after the delay elapses and the session
     * poll detects the updated server state.
     */
    fun requestDisableDeletionProtection() {
        queuePendingChange("disable_deletion_protection", null)
    }

    // ================== Pending Changes ==================

    private fun queuePendingChange(type: String, value: String?) {
        viewModelScope.launch {
            repository?.addPendingChange(type, value)?.onSuccess { change ->
                val updated = _uiState.value.pendingChanges + change
                _uiState.value = _uiState.value.copy(
                    pendingChanges = updated,
                    successMessage = "Change scheduled — takes effect in 24 hours"
                )
            }?.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to schedule change: ${error.message}"
                )
            }
        }
    }

    fun cancelPendingChange(changeId: String) {
        viewModelScope.launch {
            repository?.cancelPendingChange(changeId)?.onSuccess {
                val updated = _uiState.value.pendingChanges.filter { it.id != changeId }
                _uiState.value = _uiState.value.copy(
                    pendingChanges = updated,
                    successMessage = "Scheduled change cancelled"
                )
            }?.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to cancel change: ${error.message}"
                )
            }
        }
    }

    // ================== Private Helpers ==================

    private fun syncConfig() {
        viewModelScope.launch {
            repository?.updateConfig(
                blockedWebsites = _uiState.value.blockedWebsites.toList(),
                blockedPackages = _uiState.value.blockedPackages.toList(),
                blockedKeywords = _uiState.value.blockedKeywords.toList(),
                whitelistedWebsites = _uiState.value.whitelistedWebsites.toList(),
                whitelistedPackages = _uiState.value.whitelistedPackages.toList()
            )?.onSuccess {
                _uiState.value = _uiState.value.copy(successMessage = "Configuration synced")
            }?.onFailure { error ->
                _uiState.value = _uiState.value.copy(errorMessage = "Failed to sync: ${error.message}")
            }
        }
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(errorMessage = null, successMessage = null)
        _authState.value = _authState.value.copy(errorMessage = null)
    }

    // ================== Motivation ==================

    fun addMotivationVideo(url: String, label: String?) {
        viewModelScope.launch {
            repository?.addMotivationVideo(url, label)?.onSuccess { motivation ->
                _uiState.value = _uiState.value.copy(motivation = motivation, successMessage = "Video added")
                syncMotivationToService(motivation)
            }?.onFailure { error ->
                _uiState.value = _uiState.value.copy(errorMessage = "Failed to add video: ${error.message}")
            }
        }
    }

    fun removeMotivationVideo(index: Int) {
        viewModelScope.launch {
            repository?.removeMotivationVideo(index)?.onSuccess { motivation ->
                _uiState.value = _uiState.value.copy(motivation = motivation)
                syncMotivationToService(motivation)
            }?.onFailure { error ->
                _uiState.value = _uiState.value.copy(errorMessage = "Failed to remove video: ${error.message}")
            }
        }
    }

    fun addMotivationChannel(url: String, label: String?) {
        viewModelScope.launch {
            repository?.addMotivationChannel(url, label)?.onSuccess { motivation ->
                _uiState.value = _uiState.value.copy(motivation = motivation, successMessage = "Channel added")
                syncMotivationToService(motivation)
            }?.onFailure { error ->
                _uiState.value = _uiState.value.copy(errorMessage = "Failed to add channel: ${error.message}")
            }
        }
    }

    fun removeMotivationChannel(index: Int) {
        viewModelScope.launch {
            repository?.removeMotivationChannel(index)?.onSuccess { motivation ->
                _uiState.value = _uiState.value.copy(motivation = motivation)
                syncMotivationToService(motivation)
            }?.onFailure { error ->
                _uiState.value = _uiState.value.copy(errorMessage = "Failed to remove channel: ${error.message}")
            }
        }
    }

    fun updateMotivationDuration(seconds: Int) {
        viewModelScope.launch {
            repository?.updateMotivationDuration(seconds)?.onSuccess { motivation ->
                _uiState.value = _uiState.value.copy(motivation = motivation)
                syncMotivationToService(motivation)
            }?.onFailure { error ->
                _uiState.value = _uiState.value.copy(errorMessage = "Failed to update duration: ${error.message}")
            }
        }
    }

    private fun syncMotivationToService(motivation: MotivationConfig) {
        BlockingAccessibilityService.motivationVideos = motivation.videos.map { it.url }
        BlockingAccessibilityService.motivationChannels = motivation.channels.map { it.url }
        BlockingAccessibilityService.motivationDuration = motivation.duration
    }

    /**
     * Picks a random video from videos list (or channels list as fallback) and emits it
     * to [motivationAutoPlay]. Called when the accessibility guard fires.
     */
    fun triggerMotivationAutoPlay() {
        val motivation = _uiState.value.motivation
        val allItems = motivation.videos + motivation.channels
        if (allItems.isEmpty()) return
        val item = allItems.random()
        _motivationAutoPlay.tryEmit(item.url)
    }
}
