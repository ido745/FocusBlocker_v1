package com.focusapp.blocker.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "blocker_preferences")

class PreferencesManager(private val context: Context) {

    companion object {
        private val SERVER_URL = stringPreferencesKey("server_url")
        private val BLOCKED_PACKAGES = stringSetPreferencesKey("blocked_packages")
        private val BLOCKED_KEYWORDS = stringSetPreferencesKey("blocked_keywords")
        private val BLOCKED_WEBSITES = stringSetPreferencesKey("blocked_websites")
    }

    val serverUrl: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[SERVER_URL] ?: "http://10.0.2.2:3000"
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
}
