package com.focusapp.blocker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.focusapp.blocker.data.AuthManager
import com.focusapp.blocker.data.AuthenticatedRepository
import com.focusapp.blocker.data.Device
import com.focusapp.blocker.data.PreferencesManager
import com.focusapp.blocker.data.Session
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
    val serverUrl: String = "http://10.0.2.2:3000",
    val session: Session? = null,
    val isSessionActive: Boolean = false,
    val devices: List<Device> = emptyList(),
    val currentDeviceId: String? = null,
    val allDevicesSelected: Boolean = true,
    val selectedDeviceIds: Set<String> = emptySet(),
    val blockedPackages: Set<String> = setOf(),
    val blockedKeywords: Set<String> = setOf(),
    val blockedWebsites: Set<String> = setOf(),
    val whitelistedPackages: Set<String> = setOf(),
    val whitelistedWebsites: Set<String> = setOf(),
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

    init {
        loadPreferences()
        checkAuthStatus()
    }

    private fun loadPreferences() {
        viewModelScope.launch {
            preferencesManager.serverUrl.collect { url ->
                _uiState.value = _uiState.value.copy(serverUrl = url)
                repository = AuthenticatedRepository(getApplication(), url)

                // If authenticated, fetch data
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

                // Fetch initial data
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

                // Fetch initial data
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

                // Fetch initial data
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
        fetchActiveSession()
    }

    fun fetchConfig() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

            repository?.getConfig()?.onSuccess { (blocklists, whitelists) ->
                _uiState.value = _uiState.value.copy(
                    blockedPackages = blocklists.packages.orEmpty().toSet(),
                    blockedKeywords = blocklists.keywords.orEmpty().toSet(),
                    blockedWebsites = blocklists.websites.orEmpty().toSet(),
                    whitelistedPackages = whitelists.packages.orEmpty().toSet(),
                    whitelistedWebsites = whitelists.websites.orEmpty().toSet(),
                    isLoading = false
                )
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

    fun fetchActiveSession() {
        viewModelScope.launch {
            repository?.getActiveSession()?.onSuccess { session ->
                _uiState.value = _uiState.value.copy(
                    session = session,
                    isSessionActive = session?.isActive == true
                )
            }?.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to check session: ${error.message}"
                )
            }
        }
    }

    // ================== Session Management ==================

    fun toggleSession() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

            if (_uiState.value.isSessionActive) {
                // Stop session
                repository?.stopSession()?.onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isSessionActive = false,
                        session = null,
                        isLoading = false,
                        successMessage = "Focus session stopped"
                    )
                }?.onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = "Failed to stop session: ${error.message}"
                    )
                }
            } else {
                // Determine target devices
                val targetDevices: Any = if (_uiState.value.allDevicesSelected) {
                    "all"
                } else {
                    _uiState.value.selectedDeviceIds.toList()
                }

                val targetLabel = if (targetDevices == "all") "all devices" else "${(_uiState.value.selectedDeviceIds.size)} device(s)"

                // Start session with current blocklists
                repository?.startSession(
                    targetDevices = targetDevices,
                    blockedWebsites = _uiState.value.blockedWebsites.toList(),
                    blockedPackages = _uiState.value.blockedPackages.toList(),
                    blockedKeywords = _uiState.value.blockedKeywords.toList()
                )?.onSuccess { session ->
                    _uiState.value = _uiState.value.copy(
                        isSessionActive = true,
                        session = session,
                        isLoading = false,
                        successMessage = "Focus session started on $targetLabel!"
                    )
                }?.onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = "Failed to start session: ${error.message}"
                    )
                }
            }
        }
    }

    // ================== Device Selection ==================

    fun toggleAllDevices() {
        _uiState.value = _uiState.value.copy(
            allDevicesSelected = !_uiState.value.allDevicesSelected,
            selectedDeviceIds = emptySet()
        )
    }

    fun toggleDevice(deviceId: String) {
        val current = _uiState.value.selectedDeviceIds
        val updated = if (current.contains(deviceId)) {
            current - deviceId
        } else {
            current + deviceId
        }
        _uiState.value = _uiState.value.copy(
            selectedDeviceIds = updated,
            allDevicesSelected = false
        )
    }

    // ================== Configuration Management ==================

    fun updateServerUrl(url: String) {
        viewModelScope.launch {
            preferencesManager.saveServerUrl(url)
            _uiState.value = _uiState.value.copy(serverUrl = url)
            repository = AuthenticatedRepository(getApplication(), url)
        }
    }

    fun addBlockedPackage(packageName: String) {
        if (packageName.isBlank()) return
        val updated = _uiState.value.blockedPackages + packageName
        _uiState.value = _uiState.value.copy(blockedPackages = updated)
        syncConfig()
    }

    fun removeBlockedPackage(packageName: String) {
        val updated = _uiState.value.blockedPackages - packageName
        _uiState.value = _uiState.value.copy(blockedPackages = updated)
        syncConfig()
    }

    fun addBlockedKeyword(keyword: String) {
        if (keyword.isBlank()) return
        val updated = _uiState.value.blockedKeywords + keyword.lowercase()
        _uiState.value = _uiState.value.copy(blockedKeywords = updated)
        syncConfig()
    }

    fun removeBlockedKeyword(keyword: String) {
        val updated = _uiState.value.blockedKeywords - keyword
        _uiState.value = _uiState.value.copy(blockedKeywords = updated)
        syncConfig()
    }

    fun addBlockedWebsite(website: String) {
        if (website.isBlank()) return
        val updated = _uiState.value.blockedWebsites + website.lowercase()
        _uiState.value = _uiState.value.copy(blockedWebsites = updated)
        syncConfig()
    }

    fun removeBlockedWebsite(website: String) {
        val updated = _uiState.value.blockedWebsites - website
        _uiState.value = _uiState.value.copy(blockedWebsites = updated)
        syncConfig()
    }

    fun addWhitelistedPackage(packageName: String) {
        if (packageName.isBlank()) return
        val updated = _uiState.value.whitelistedPackages + packageName
        _uiState.value = _uiState.value.copy(whitelistedPackages = updated)
        syncConfig()
    }

    fun removeWhitelistedPackage(packageName: String) {
        val updated = _uiState.value.whitelistedPackages - packageName
        _uiState.value = _uiState.value.copy(whitelistedPackages = updated)
        syncConfig()
    }

    fun addWhitelistedWebsite(website: String) {
        if (website.isBlank()) return
        val updated = _uiState.value.whitelistedWebsites + website.lowercase()
        _uiState.value = _uiState.value.copy(whitelistedWebsites = updated)
        syncConfig()
    }

    fun removeWhitelistedWebsite(website: String) {
        val updated = _uiState.value.whitelistedWebsites - website
        _uiState.value = _uiState.value.copy(whitelistedWebsites = updated)
        syncConfig()
    }

    private fun syncConfig() {
        viewModelScope.launch {
            repository?.updateConfig(
                blockedWebsites = _uiState.value.blockedWebsites.toList(),
                blockedPackages = _uiState.value.blockedPackages.toList(),
                blockedKeywords = _uiState.value.blockedKeywords.toList(),
                whitelistedWebsites = _uiState.value.whitelistedWebsites.toList(),
                whitelistedPackages = _uiState.value.whitelistedPackages.toList()
            )?.onSuccess {
                _uiState.value = _uiState.value.copy(
                    successMessage = "Configuration synced"
                )
            }?.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to sync: ${error.message}"
                )
            }
        }
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(errorMessage = null, successMessage = null)
        _authState.value = _authState.value.copy(errorMessage = null)
    }
}
