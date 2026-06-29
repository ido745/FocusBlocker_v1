package com.focusapp.blocker.data

// ================== Legacy data classes (kept for compatibility) ==================

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

// ================== Authenticated Endpoints ==================

data class RegisterRequest(
    val email: String,
    val password: String,
    val name: String
)

data class LoginRequest(
    val email: String,
    val password: String
)

data class GoogleAuthRequest(
    val idToken: String
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
    val name: String,
    val picture: String? = null
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
    val targetDevices: Any,
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
    val endTime: String? = null,
    val deletionProtectionEnabled: Boolean? = null
)

data class MotivationItem(
    val url: String,
    val label: String? = null
)

data class MotivationConfig(
    val videos: List<MotivationItem> = emptyList(),
    val channels: List<MotivationItem> = emptyList(),
    val galleryVideos: List<MotivationItem> = emptyList(),
    val phrases: List<String> = emptyList(),
    val duration: Int = 10
)

data class ConfigGetResponse(
    val success: Boolean,
    val blocklists: Blocklists,
    val whitelists: Whitelists,
    val deletionProtectionEnabled: Boolean? = null,
    val motivation: MotivationConfig? = null
)

data class Blocklists(
    val websites: List<String>? = null,
    val packages: List<String>? = null,
    val keywords: List<String>? = null
)

data class Whitelists(
    val websites: List<String>? = null,
    val packages: List<String>? = null
)

data class ConfigUpdateRequest(
    val blockedWebsites: List<String>? = null,
    val blockedPackages: List<String>? = null,
    val blockedKeywords: List<String>? = null,
    val whitelistedWebsites: List<String>? = null,
    val whitelistedPackages: List<String>? = null,
    val deletionProtectionEnabled: Boolean? = null
)

data class ConfigUpdateResponse(
    val success: Boolean,
    val blocklists: Blocklists,
    val whitelists: Whitelists,
    val deletionProtectionEnabled: Boolean? = null
)

// ================== Pending Changes ==================

/**
 * Types of constraint-relaxing changes that require a 24-hour delay:
 * - remove_blocked_website / remove_blocked_package / remove_blocked_keyword
 * - add_whitelisted_website / add_whitelisted_package
 * - disable_deletion_protection
 */
data class PendingChange(
    val id: String,
    val type: String,
    val value: String?,
    val createdAt: String,
    val scheduledFor: String
)

data class PendingChangesResponse(
    val success: Boolean,
    val pendingChanges: List<PendingChange>
)

data class AddPendingChangeRequest(
    val type: String,
    val value: String?
)

data class AddPendingChangeResponse(
    val success: Boolean,
    val change: PendingChange?,
    val message: String? = null
)

data class CancelPendingChangeResponse(
    val success: Boolean,
    val message: String? = null
)

// ================== Motivation ==================

data class AddMotivationItemRequest(
    val url: String,
    val label: String? = null
)

data class MotivationItemResponse(
    val success: Boolean,
    val motivation: MotivationConfig? = null,
    val message: String? = null
)

data class UpdateMotivationDurationRequest(
    val duration: Int
)

data class RandomVideoResponse(
    val success: Boolean,
    val videoUrl: String? = null,
    val error: String? = null
)

data class DownloadUrlResponse(
    val success: Boolean,
    val downloadUrl: String? = null,
    val error: String? = null
)

