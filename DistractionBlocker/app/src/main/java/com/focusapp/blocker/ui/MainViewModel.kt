package com.focusapp.blocker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.focusapp.blocker.data.BlockerRepository
import com.focusapp.blocker.data.PreferencesManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UiState(
    val serverUrl: String = "http://10.0.2.2:3000",
    val isSessionActive: Boolean = false,
    val blockedPackages: Set<String> = setOf(),
    val blockedKeywords: Set<String> = setOf(),
    val blockedWebsites: Set<String> = setOf(),
    val whitelistedPackages: Set<String> = setOf(),
    val whitelistedWebsites: Set<String> = setOf(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val preferencesManager = PreferencesManager(application)
    private var repository: BlockerRepository? = null

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        loadPreferences()
    }

    private fun loadPreferences() {
        // CRITICAL FIX: Only load server URL from preferences
        // All blocked/whitelisted items come from the backend to avoid overwriting
        viewModelScope.launch {
            preferencesManager.serverUrl.collect { url ->
                _uiState.value = _uiState.value.copy(serverUrl = url)
                repository = BlockerRepository(url)
                // Fetch current state from backend (don't sync local to backend)
                fetchStatus()
            }
        }
    }

    fun updateServerUrl(url: String) {
        viewModelScope.launch {
            preferencesManager.saveServerUrl(url)
            _uiState.value = _uiState.value.copy(serverUrl = url)
            repository = BlockerRepository(url)
        }
    }

    fun fetchStatus() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

            repository?.getStatus()?.onSuccess { state ->
                // CRITICAL: Load from backend, don't overwrite with local empty data
                _uiState.value = _uiState.value.copy(
                    isSessionActive = state.isSessionActive,
                    blockedPackages = state.blockedPackages.toSet(),
                    blockedKeywords = state.blockedKeywords.toSet(),
                    blockedWebsites = state.blockedWebsites.toSet(),
                    whitelistedPackages = state.whitelistedPackages.toSet(),
                    whitelistedWebsites = state.whitelistedWebsites.toSet(),
                    isLoading = false
                )
                // Save to local preferences so they persist
                preferencesManager.saveBlockedPackages(state.blockedPackages.toSet())
                preferencesManager.saveBlockedKeywords(state.blockedKeywords.toSet())
                preferencesManager.saveBlockedWebsites(state.blockedWebsites.toSet())
            }?.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Failed to connect to server: ${error.message}"
                )
            }
        }
    }

    fun toggleSession() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

            repository?.toggleSession()?.onSuccess { isActive ->
                _uiState.value = _uiState.value.copy(
                    isSessionActive = isActive,
                    isLoading = false,
                    successMessage = if (isActive) "Focus session started!" else "Focus session stopped"
                )
            }?.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Failed to toggle session: ${error.message}"
                )
            }
        }
    }

    fun addBlockedPackage(packageName: String) {
        if (packageName.isBlank()) return

        val updated = _uiState.value.blockedPackages + packageName
        viewModelScope.launch {
            preferencesManager.saveBlockedPackages(updated)
            _uiState.value = _uiState.value.copy(blockedPackages = updated)
            syncWithServer()
        }
    }

    fun removeBlockedPackage(packageName: String) {
        val updated = _uiState.value.blockedPackages - packageName
        viewModelScope.launch {
            preferencesManager.saveBlockedPackages(updated)
            _uiState.value = _uiState.value.copy(blockedPackages = updated)
            syncWithServer()
        }
    }

    fun addBlockedKeyword(keyword: String) {
        if (keyword.isBlank()) return

        val updated = _uiState.value.blockedKeywords + keyword.lowercase()
        viewModelScope.launch {
            preferencesManager.saveBlockedKeywords(updated)
            _uiState.value = _uiState.value.copy(blockedKeywords = updated)
            syncWithServer()
        }
    }

    fun removeBlockedKeyword(keyword: String) {
        val updated = _uiState.value.blockedKeywords - keyword
        viewModelScope.launch {
            preferencesManager.saveBlockedKeywords(updated)
            _uiState.value = _uiState.value.copy(blockedKeywords = updated)
            syncWithServer()
        }
    }

    fun addBlockedWebsite(website: String) {
        if (website.isBlank()) return

        val updated = _uiState.value.blockedWebsites + website.lowercase()
        viewModelScope.launch {
            preferencesManager.saveBlockedWebsites(updated)
            _uiState.value = _uiState.value.copy(blockedWebsites = updated)
            syncWithServer()
        }
    }

    fun removeBlockedWebsite(website: String) {
        val updated = _uiState.value.blockedWebsites - website
        viewModelScope.launch {
            preferencesManager.saveBlockedWebsites(updated)
            _uiState.value = _uiState.value.copy(blockedWebsites = updated)
            syncWithServer()
        }
    }

    // Whitelist management functions
    fun addWhitelistedPackage(packageName: String) {
        if (packageName.isBlank()) return

        val updated = _uiState.value.whitelistedPackages + packageName
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(whitelistedPackages = updated)
            syncWithServer()
        }
    }

    fun removeWhitelistedPackage(packageName: String) {
        val updated = _uiState.value.whitelistedPackages - packageName
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(whitelistedPackages = updated)
            syncWithServer()
        }
    }

    fun addWhitelistedWebsite(website: String) {
        if (website.isBlank()) return

        val updated = _uiState.value.whitelistedWebsites + website.lowercase()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(whitelistedWebsites = updated)
            syncWithServer()
        }
    }

    fun removeWhitelistedWebsite(website: String) {
        val updated = _uiState.value.whitelistedWebsites - website
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(whitelistedWebsites = updated)
            syncWithServer()
        }
    }

    private fun syncWithServer() {
        viewModelScope.launch {
            repository?.updateConfig(
                blockedPackages = _uiState.value.blockedPackages.toList(),
                blockedKeywords = _uiState.value.blockedKeywords.toList(),
                blockedWebsites = _uiState.value.blockedWebsites.toList(),
                whitelistedPackages = _uiState.value.whitelistedPackages.toList(),
                whitelistedWebsites = _uiState.value.whitelistedWebsites.toList()
            )?.onSuccess {
                _uiState.value = _uiState.value.copy(
                    successMessage = "Configuration synced with server"
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
    }
}
