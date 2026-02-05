package com.focusapp.blocker.data

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class BlockerRepository(private val serverUrl: String) {

    private val TAG = "BlockerRepository"

    private val apiService: ApiService by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
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

    suspend fun getStatus(): Result<SessionState> {
        return try {
            val response = apiService.getStatus()
            if (response.success) {
                Result.success(response.data)
            } else {
                Result.failure(Exception("Failed to get status"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting status", e)
            Result.failure(e)
        }
    }

    suspend fun toggleSession(): Result<Boolean> {
        return try {
            val response = apiService.toggleSession()
            if (response.success) {
                Result.success(response.data.isSessionActive)
            } else {
                Result.failure(Exception("Failed to toggle session"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling session", e)
            Result.failure(e)
        }
    }

    suspend fun updateConfig(
        blockedPackages: List<String>? = null,
        blockedKeywords: List<String>? = null,
        blockedWebsites: List<String>? = null,
        whitelistedPackages: List<String>? = null,
        whitelistedWebsites: List<String>? = null
    ): Result<SessionState> {
        return try {
            val request = ConfigRequest(
                blockedPackages,
                blockedKeywords,
                blockedWebsites,
                whitelistedPackages,
                whitelistedWebsites
            )
            val response = apiService.updateConfig(request)
            if (response.success) {
                Result.success(response.data)
            } else {
                Result.failure(Exception("Failed to update config"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating config", e)
            Result.failure(e)
        }
    }
}
