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

        // Auth interceptor to add Authorization header
        val authInterceptor = Interceptor { chain ->
            val requestBuilder = chain.request().newBuilder()

            // Get auth token synchronously (we're in an interceptor)
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

        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .addInterceptor(authInterceptor)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
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
                // Save auth token
                authManager.saveAuthToken(response.token, email, name)

                // Register device
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
                // Save auth token
                authManager.saveAuthToken(response.token, email, response.user.name)

                // Register device
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

    // ================== Session Management ==================

    suspend fun startSession(
        targetDevices: Any = "all",
        blockedWebsites: List<String>? = null,
        blockedPackages: List<String>? = null,
        blockedKeywords: List<String>? = null,
        duration: Int? = null
    ): Result<Session?> {
        return try {
            val request = SessionStartRequest(
                targetDevices = targetDevices,
                blockedWebsites = blockedWebsites,
                blockedPackages = blockedPackages,
                blockedKeywords = blockedKeywords,
                duration = duration
            )

            val response = apiService.startSession(request)

            if (response.success) {
                Result.success(response.session)
            } else {
                Result.failure(Exception(response.message ?: "Failed to start session"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting session", e)
            Result.failure(e)
        }
    }

    suspend fun stopSession(): Result<Unit> {
        return try {
            val response = apiService.stopSession()
            if (response.success) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(response.message ?: "Failed to stop session"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping session", e)
            Result.failure(e)
        }
    }

    suspend fun getActiveSession(): Result<Session?> {
        return try {
            val deviceId = authManager.getDeviceId()
            val response = apiService.getActiveSession(deviceId)

            if (response.success) {
                Result.success(response.session)
            } else {
                Result.success(null) // No active session
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting active session", e)
            Result.failure(e)
        }
    }

    // ================== Configuration ==================

    suspend fun getConfig(): Result<Pair<Blocklists, Whitelists>> {
        return try {
            val response = apiService.getConfig()
            if (response.success) {
                Result.success(Pair(response.blocklists, response.whitelists))
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
        whitelistedPackages: List<String>? = null
    ): Result<Pair<Blocklists, Whitelists>> {
        return try {
            val request = ConfigUpdateRequest(
                blockedWebsites = blockedWebsites,
                blockedPackages = blockedPackages,
                blockedKeywords = blockedKeywords,
                whitelistedWebsites = whitelistedWebsites,
                whitelistedPackages = whitelistedPackages
            )

            val response = apiService.updateConfigAuth(request)

            if (response.success) {
                Result.success(Pair(response.blocklists, response.whitelists))
            } else {
                Result.failure(Exception("Failed to update config"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating config", e)
            Result.failure(e)
        }
    }
}
