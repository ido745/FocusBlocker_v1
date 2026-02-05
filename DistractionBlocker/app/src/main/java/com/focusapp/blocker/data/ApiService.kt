package com.focusapp.blocker.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

// ================== Legacy Endpoints (unauthenticated) ==================

data class StatusResponse(
    val success: Boolean,
    val data: SessionState
)

data class SessionState(
    val isSessionActive: Boolean,
    val blockedPackages: List<String>,
    val blockedKeywords: List<String>,
    val blockedWebsites: List<String>,
    val whitelistedPackages: List<String> = emptyList(),
    val whitelistedWebsites: List<String> = emptyList()
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
    val blockedWebsites: List<String>? = null,
    val whitelistedPackages: List<String>? = null,
    val whitelistedWebsites: List<String>? = null
)

data class ConfigResponse(
    val success: Boolean,
    val message: String,
    val data: SessionState
)

// ================== New Authenticated Endpoints ==================

data class RegisterRequest(
    val email: String,
    val password: String,
    val name: String
)

data class LoginRequest(
    val email: String,
    val password: String
)

data class AuthResponse(
    val success: Boolean,
    val token: String? = null,
    val user: User? = null,
    val error: String? = null
)

data class User(
    val id: String,
    val email: String,
    val name: String
)

data class DeviceRegistrationRequest(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val platform: String
)

data class DeviceRegistrationResponse(
    val success: Boolean,
    val device: Device
)

data class Device(
    val id: String,
    val userId: String,
    val name: String,
    val type: String,
    val platform: String,
    val isOnline: Boolean
)

data class DevicesResponse(
    val success: Boolean,
    val devices: List<Device>
)

data class SessionStartRequest(
    val targetDevices: Any, // "all" or List<String>
    val blockedWebsites: List<String>? = null,
    val blockedPackages: List<String>? = null,
    val blockedKeywords: List<String>? = null,
    val duration: Int? = null
)

data class SessionResponse(
    val success: Boolean,
    val message: String? = null,
    val session: Session? = null
)

data class Session(
    val id: String,
    val isActive: Boolean,
    val blockedWebsites: List<String>? = null,
    val blockedPackages: List<String>? = null,
    val blockedKeywords: List<String>? = null,
    val whitelistedWebsites: List<String>? = null,
    val whitelistedPackages: List<String>? = null,
    val startTime: String? = null,
    val endTime: String? = null
)

data class ConfigGetResponse(
    val success: Boolean,
    val blocklists: Blocklists,
    val whitelists: Whitelists
)

data class Blocklists(
    val websites: List<String>,
    val packages: List<String>,
    val keywords: List<String>
)

data class Whitelists(
    val websites: List<String>,
    val packages: List<String>
)

data class ConfigUpdateRequest(
    val blockedWebsites: List<String>? = null,
    val blockedPackages: List<String>? = null,
    val blockedKeywords: List<String>? = null,
    val whitelistedWebsites: List<String>? = null,
    val whitelistedPackages: List<String>? = null
)

data class ConfigUpdateResponse(
    val success: Boolean,
    val blocklists: Blocklists,
    val whitelists: Whitelists
)

interface ApiService {
    // ================== Legacy Endpoints (backward compatibility) ==================
    @GET("status")
    suspend fun getStatus(): StatusResponse

    @POST("toggle")
    suspend fun toggleSession(): ToggleResponse

    @POST("config")
    suspend fun updateConfig(@Body config: ConfigRequest): ConfigResponse

    // ================== New Authenticated Endpoints ==================

    // Authentication
    @POST("auth/register")
    suspend fun register(@Body request: RegisterRequest): AuthResponse

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): AuthResponse

    // Device Management
    @POST("devices/register")
    suspend fun registerDevice(@Body request: DeviceRegistrationRequest): DeviceRegistrationResponse

    @GET("devices")
    suspend fun getDevices(): DevicesResponse

    // Session Management (Multi-Device)
    @POST("sessions/start")
    suspend fun startSession(@Body request: SessionStartRequest): SessionResponse

    @POST("sessions/stop")
    suspend fun stopSession(): SessionResponse

    @GET("sessions/active")
    suspend fun getActiveSession(@Query("deviceId") deviceId: String): SessionResponse

    // Configuration Management
    @GET("config")
    suspend fun getConfig(): ConfigGetResponse

    @POST("config")
    suspend fun updateConfigAuth(@Body request: ConfigUpdateRequest): ConfigUpdateResponse
}
