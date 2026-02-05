package com.focusapp.blocker.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

data class StatusResponse(
    val success: Boolean,
    val data: SessionState
)

data class SessionState(
    val isSessionActive: Boolean,
    val blockedPackages: List<String>,
    val blockedKeywords: List<String>,
    val blockedWebsites: List<String>
)

data class ToggleResponse(
    val success: Boolean,
    val message: String,
    val data: ToggleData
)

data class ToggleData(
    val isSessionActive: Boolean
)

data class ConfigRequest(
    val blockedPackages: List<String>? = null,
    val blockedKeywords: List<String>? = null,
    val blockedWebsites: List<String>? = null
)

data class ConfigResponse(
    val success: Boolean,
    val message: String,
    val data: SessionState
)

interface ApiService {
    @GET("status")
    suspend fun getStatus(): StatusResponse

    @POST("toggle")
    suspend fun toggleSession(): ToggleResponse

    @POST("config")
    suspend fun updateConfig(@Body config: ConfigRequest): ConfigResponse
}
