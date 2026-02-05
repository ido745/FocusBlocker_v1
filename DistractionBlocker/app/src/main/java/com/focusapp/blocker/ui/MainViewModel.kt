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
        viewModelScope.launch {
            preferencesManager.serverUrl.collect { url ->
                _uiState.value = _uiState.value.copy(serverUrl = url)
                repository = BlockerRepository(url)
                fetchStatus()
            }
        }

        viewModelScope.launch {
            preferencesManager.blockedPackages.collect { packages ->
                _uiState.value = _uiState.value.copy(blockedPackages = packages)
            }
        }

        viewModelScope.launch {
            preferencesManager.blockedKeywords.collect { keywords ->
                _uiState.value = _uiState.value.copy(blockedKeywords = keywords)
            }
        }

        viewModelScope.launch {
            preferencesManager.blockedWebsites.collect { websites ->
                _uiState.value = _uiState.value.copy(blockedWebsites = websites)
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
                _uiState.value = _uiState.value.copy(
                    isSessionActive = state.isSessionActive,
                    blockedPackages = state.blockedPackages.toSet(),
                    blockedKeywords = state.blockedKeywords.toSet(),
                    blockedWebsites = state.blockedWebsites.toSet(),
                    isLoading = false
                )
            }?.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Failed to connect to server: ${error.message}"
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

    private fun syncWithServer() {
        viewModelScope.launch {
            repository?.updateConfig(
                blockedPackages = _uiState.value.blockedPackages.toList(),
                blockedKeywords = _uiState.value.blockedKeywords.toList(),
                blockedWebsites = _uiState.value.blockedWebsites.toList()
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
