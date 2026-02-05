package com.focusapp.blocker.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(name = "auth")

class AuthManager(private val context: Context) {

    private val AUTH_TOKEN_KEY = stringPreferencesKey("auth_token")
    private val DEVICE_ID_KEY = stringPreferencesKey("device_id")
    private val USER_EMAIL_KEY = stringPreferencesKey("user_email")
    private val USER_NAME_KEY = stringPreferencesKey("user_name")

    val authToken: Flow<String?> = context.authDataStore.data.map { prefs ->
        prefs[AUTH_TOKEN_KEY]
    }

    val deviceId: Flow<String> = context.authDataStore.data.map { prefs ->
        prefs[DEVICE_ID_KEY] ?: generateAndSaveDeviceId()
    }

    val userEmail: Flow<String?> = context.authDataStore.data.map { prefs ->
        prefs[USER_EMAIL_KEY]
    }

    val userName: Flow<String?> = context.authDataStore.data.map { prefs ->
        prefs[USER_NAME_KEY]
    }

    private fun generateAndSaveDeviceId(): String {
        val newId = UUID.randomUUID().toString()
        // Note: This is called from Flow, so we can't suspend here
        // The ID will be saved on next saveAuthToken call
        return newId
    }

    suspend fun saveAuthToken(token: String, email: String, name: String) {
        context.authDataStore.edit { prefs ->
            prefs[AUTH_TOKEN_KEY] = token
            prefs[USER_EMAIL_KEY] = email
            prefs[USER_NAME_KEY] = name

            // Ensure device ID exists
            if (!prefs.contains(DEVICE_ID_KEY)) {
                prefs[DEVICE_ID_KEY] = UUID.randomUUID().toString()
            }
        }
    }

    suspend fun getDeviceId(): String {
        val prefs = context.authDataStore.data.first()
        var deviceId = prefs[DEVICE_ID_KEY]

        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString()
            context.authDataStore.edit { p ->
                p[DEVICE_ID_KEY] = deviceId
            }
        }

        return deviceId
    }

    suspend fun clearAuthToken() {
        context.authDataStore.edit { prefs ->
            prefs.remove(AUTH_TOKEN_KEY)
            prefs.remove(USER_EMAIL_KEY)
            prefs.remove(USER_NAME_KEY)
            // Keep device ID for next login
        }
    }

    suspend fun isAuthenticated(): Boolean {
        val token = authToken.first()
        return !token.isNullOrBlank()
    }
}
