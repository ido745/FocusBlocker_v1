package com.focusapp.blocker.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "blocker_preferences")

class PreferencesManager(private val context: Context) {

    companion object {
        private val SERVER_URL = stringPreferencesKey("server_url")
        private val BLOCKED_PACKAGES = stringSetPreferencesKey("blocked_packages")
        private val BLOCKED_KEYWORDS = stringSetPreferencesKey("blocked_keywords")
        private val BLOCKED_WEBSITES = stringSetPreferencesKey("blocked_websites")
        private val WHITELISTED_PACKAGES = stringSetPreferencesKey("whitelisted_packages")
        private val WHITELISTED_WEBSITES = stringSetPreferencesKey("whitelisted_websites")
        private val DELETION_PROTECTION = stringPreferencesKey("deletion_protection")
    }

    val serverUrl: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[SERVER_URL] ?: "https://focus-blocker-backend.onrender.com"
    }

    val blockedPackages: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[BLOCKED_PACKAGES] ?: setOf(
            "com.instagram.android",
            "com.facebook.katana",
            "com.twitter.android"
        )
    }

    val blockedKeywords: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[BLOCKED_KEYWORDS] ?: setOf("gambling", "casino", "bet")
    }

    val blockedWebsites: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[BLOCKED_WEBSITES] ?: setOf(
            "facebook.com",
            "instagram.com",
            "twitter.com",
            "reddit.com"
        )
    }

    val whitelistedPackages: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[WHITELISTED_PACKAGES] ?: emptySet()
    }

    val whitelistedWebsites: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[WHITELISTED_WEBSITES] ?: emptySet()
    }

    val deletionProtection: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[DELETION_PROTECTION]?.toBooleanStrictOrNull() ?: false
    }

    suspend fun saveServerUrl(url: String) {
        context.dataStore.edit { preferences ->
            preferences[SERVER_URL] = url
        }
    }

    suspend fun saveBlockedPackages(packages: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[BLOCKED_PACKAGES] = packages
        }
    }

    suspend fun saveBlockedKeywords(keywords: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[BLOCKED_KEYWORDS] = keywords
        }
    }

    suspend fun saveBlockedWebsites(websites: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[BLOCKED_WEBSITES] = websites
        }
    }

    suspend fun saveWhitelistedPackages(packages: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[WHITELISTED_PACKAGES] = packages
        }
    }

    suspend fun saveWhitelistedWebsites(websites: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[WHITELISTED_WEBSITES] = websites
        }
    }

    suspend fun saveDeletionProtection(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[DELETION_PROTECTION] = enabled.toString()
        }
    }

    suspend fun loadCachedConfig(): CachedConfig {
        val prefs = context.dataStore.data.first()
        return CachedConfig(
            blockedPackages = prefs[BLOCKED_PACKAGES] ?: emptySet(),
            blockedKeywords = prefs[BLOCKED_KEYWORDS] ?: emptySet(),
            blockedWebsites = prefs[BLOCKED_WEBSITES] ?: emptySet(),
            whitelistedPackages = prefs[WHITELISTED_PACKAGES] ?: emptySet(),
            whitelistedWebsites = prefs[WHITELISTED_WEBSITES] ?: emptySet(),
            deletionProtection = prefs[DELETION_PROTECTION]?.toBooleanStrictOrNull() ?: false
        )
    }

    data class CachedConfig(
        val blockedPackages: Set<String>,
        val blockedKeywords: Set<String>,
        val blockedWebsites: Set<String>,
        val whitelistedPackages: Set<String>,
        val whitelistedWebsites: Set<String>,
        val deletionProtection: Boolean
    )
}
