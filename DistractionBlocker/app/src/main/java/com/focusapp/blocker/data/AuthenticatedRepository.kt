package com.focusapp.blocker.data

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.first
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class AuthenticatedRepository(
    private val context: Context,
    private val serverUrl: String
) {
    private val TAG = "AuthenticatedRepository"
    private val authManager = AuthManager(context)

    private val apiService: ApiService by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val authInterceptor = Interceptor { chain ->
            val requestBuilder = chain.request().newBuilder()
            val token = runCatching {
                kotlinx.coroutines.runBlocking {
                    authManager.authToken.first()
                }
            }.getOrNull()
            if (!token.isNullOrBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $token")
            }
            chain.proceed(requestBuilder.build())
        }

        val retryInterceptor = Interceptor { chain ->
            var request = chain.request()
            var response: okhttp3.Response? = null
            var exception: java.io.IOException? = null
            val maxRetries = 2

            for (attempt in 0..maxRetries) {
                try {
                    response?.close()
                    response = chain.proceed(request)
                    if (response.isSuccessful || response.code != 503) {
                        return@Interceptor response
                    }
                    if (attempt < maxRetries) {
                        Log.d(TAG, "Server may be waking up (503), retrying in 3s... (attempt ${attempt + 1}/$maxRetries)")
                        Thread.sleep(3000)
                    }
                } catch (e: java.io.IOException) {
                    exception = e
                    if (attempt < maxRetries) {
                        Log.d(TAG, "Connection failed, retrying in 3s... (attempt ${attempt + 1}/$maxRetries)")
                        Thread.sleep(3000)
                    }
                }
            }

            response ?: throw (exception ?: java.io.IOException("Unknown error after retries"))
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .addInterceptor(authInterceptor)
            .addInterceptor(retryInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl(serverUrl.let { if (it.endsWith("/")) it else "$it/" })
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    // ================== Authentication ==================

    suspend fun register(email: String, password: String, name: String): Result<User> {
        return try {
            val request = RegisterRequest(email, password, name)
            val response = apiService.register(request)
            if (response.success && response.token != null && response.user != null) {
                authManager.saveAuthToken(response.token, email, name)
                registerDevice()
                Result.success(response.user)
            } else {
                Result.failure(Exception(response.error ?: "Registration failed"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering", e)
            Result.failure(e)
        }
    }

    suspend fun login(email: String, password: String): Result<User> {
        return try {
            val request = LoginRequest(email, password)
            val response = apiService.login(request)
            if (response.success && response.token != null && response.user != null) {
                authManager.saveAuthToken(response.token, email, response.user.name)
                registerDevice()
                Result.success(response.user)
            } else {
                Result.failure(Exception(response.error ?: "Login failed"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error logging in", e)
            Result.failure(e)
        }
    }

    suspend fun googleAuth(idToken: String): Result<User> {
        return try {
            val request = GoogleAuthRequest(idToken)
            val response = apiService.googleAuth(request)
            if (response.success && response.token != null && response.user != null) {
                authManager.saveAuthToken(response.token, response.user.email, response.user.name)
                registerDevice()
                Result.success(response.user)
            } else {
                Result.failure(Exception(response.error ?: "Google auth failed"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error with Google auth", e)
            Result.failure(e)
        }
    }

    suspend fun registerDevice(): Result<Device> {
        return try {
            val deviceId = authManager.getDeviceId()
            val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
            val request = DeviceRegistrationRequest(
                deviceId = deviceId,
                deviceName = deviceName,
                deviceType = "android",
                platform = "android"
            )
            val response = apiService.registerDevice(request)
            if (response.success) {
                Log.d(TAG, "Device registered: ${response.device.name}")
                Result.success(response.device)
            } else {
                Result.failure(Exception("Device registration failed"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering device", e)
            Result.failure(e)
        }
    }

    suspend fun logout(): Result<Unit> {
        return try {
            authManager.clearAuthToken()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error logging out", e)
            Result.failure(e)
        }
    }

    // ================== Device Management ==================

    suspend fun getDevices(): Result<List<Device>> {
        return try {
            val response = apiService.getDevices()
            if (response.success) {
                Result.success(response.devices)
            } else {
                Result.failure(Exception("Failed to get devices"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting devices", e)
            Result.failure(e)
        }
    }

    // ================== Session (Always-On) ==================

    suspend fun getActiveSession(): Result<Session?> {
        return try {
            val deviceId = authManager.getDeviceId()
            val response = apiService.getActiveSession(deviceId)
            if (response.success) {
                Result.success(response.session)
            } else {
                Result.success(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting active session", e)
            Result.failure(e)
        }
    }

    // ================== Configuration ==================

    suspend fun getConfig(): Result<Triple<Blocklists, Whitelists, Boolean>> {
        return try {
            val response = apiService.getConfig()
            if (response.success) {
                Result.success(
                    Triple(
                        response.blocklists,
                        response.whitelists,
                        response.deletionProtectionEnabled ?: false
                    )
                )
            } else {
                Result.failure(Exception("Failed to get config"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting config", e)
            Result.failure(e)
        }
    }

    suspend fun updateConfig(
        blockedWebsites: List<String>? = null,
        blockedPackages: List<String>? = null,
        blockedKeywords: List<String>? = null,
        whitelistedWebsites: List<String>? = null,
        whitelistedPackages: List<String>? = null,
        deletionProtectionEnabled: Boolean? = null
    ): Result<Triple<Blocklists, Whitelists, Boolean>> {
        return try {
            val request = ConfigUpdateRequest(
                blockedWebsites = blockedWebsites,
                blockedPackages = blockedPackages,
                blockedKeywords = blockedKeywords,
                whitelistedWebsites = whitelistedWebsites,
                whitelistedPackages = whitelistedPackages,
                deletionProtectionEnabled = deletionProtectionEnabled
            )
            val response = apiService.updateConfigAuth(request)
            if (response.success) {
                Result.success(
                    Triple(
                        response.blocklists,
                        response.whitelists,
                        response.deletionProtectionEnabled ?: false
                    )
                )
            } else {
                Result.failure(Exception("Failed to update config"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating config", e)
            Result.failure(e)
        }
    }

    // ================== Pending Changes ==================

    suspend fun getPendingChanges(): Result<List<PendingChange>> {
        return try {
            val response = apiService.getPendingChanges()
            if (response.success) {
                Result.success(response.pendingChanges)
            } else {
                Result.failure(Exception("Failed to get pending changes"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting pending changes", e)
            Result.failure(e)
        }
    }

    /**
     * Queues a constraint-relaxing change with a 24-hour delay.
     */
    suspend fun addPendingChange(type: String, value: String?): Result<PendingChange> {
        return try {
            val request = AddPendingChangeRequest(type = type, value = value)
            val response = apiService.addPendingChange(request)
            if (response.success && response.change != null) {
                Result.success(response.change)
            } else {
                Result.failure(Exception(response.message ?: "Failed to queue change"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adding pending change", e)
            Result.failure(e)
        }
    }

    /**
     * Cancels a queued pending change (user changed their mind within 24 hours).
     */
    suspend fun cancelPendingChange(changeId: String): Result<Unit> {
        return try {
            val response = apiService.cancelPendingChange(changeId)
            if (response.success) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(response.message ?: "Failed to cancel change"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling pending change", e)
            Result.failure(e)
        }
    }
}
