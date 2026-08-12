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
        private val WHITELISTED_KEYWORDS = stringSetPreferencesKey("whitelisted_keywords")
        private val DELETION_PROTECTION = stringPreferencesKey("deletion_protection")
        private val MOTIVATION_VIDEOS = stringSetPreferencesKey("motivation_videos")
        private val MOTIVATION_CHANNELS = stringSetPreferencesKey("motivation_channels")
        private val DOWNLOADED_VIDEOS = stringSetPreferencesKey("downloaded_videos")
        private val PENDING_CHANGES_JSON = stringPreferencesKey("pending_changes_json")
        private val MOTIVATION_CONFIG_JSON = stringPreferencesKey("motivation_config_json")
        private val ADULT_BLOCKING_ENABLED = stringPreferencesKey("adult_blocking_enabled")
        private val ADULT_BLOCKING_LEVEL = stringPreferencesKey("adult_blocking_level")
        private val BLOCK_YOUTUBE_SHORTS = stringPreferencesKey("block_youtube_shorts")
        private val BLOCK_INSTAGRAM_REELS = stringPreferencesKey("block_instagram_reels")
        private val LOCK_ENABLED = stringPreferencesKey("lock_enabled")
        private val DURATION_LOCKED = stringPreferencesKey("duration_locked")
        private val CONTENT_LOCKED = stringPreferencesKey("content_locked")
        private val SETTINGS_PROTECTION_LEVEL = stringPreferencesKey("settings_protection_level")
        private val MOTIVATION_ON_BLOCK = stringPreferencesKey("motivation_on_block")
        private val MOTIVATION_ON_SETTINGS = stringPreferencesKey("motivation_on_settings")
        private val HIDE_APP_ICON = stringPreferencesKey("hide_app_icon")
        private val TERMS_ACCEPTED_VERSION = stringPreferencesKey("terms_accepted_version")
    }

    val termsAcceptedVersion: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[TERMS_ACCEPTED_VERSION]?.toIntOrNull() ?: 0
    }

    suspend fun acceptTerms(version: Int) {
        context.dataStore.edit { it[TERMS_ACCEPTED_VERSION] = version.toString() }
    }

    val serverUrl: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[SERVER_URL] ?: "https://focus-blocker-backend.onrender.com"
    }

    // These MUST default to empty, and must agree with loadCachedConfig() below.
    //
    // They used to default to Instagram/Facebook/Twitter/Reddit and the keywords
    // gambling/casino/bet. Because loadCachedConfig() — which populates the UI — defaulted
    // to emptySet() while these Flows — which the accessibility service collects — did not,
    // a new install silently blocked four sites, three apps and three keywords that the user
    // never chose AND could not see listed anywhere in the app to remove.
    val blockedPackages: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[BLOCKED_PACKAGES] ?: emptySet()
    }

    val blockedKeywords: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[BLOCKED_KEYWORDS] ?: emptySet()
    }

    val blockedWebsites: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[BLOCKED_WEBSITES] ?: emptySet()
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

    val whitelistedKeywords: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[WHITELISTED_KEYWORDS] ?: emptySet()
    }

    suspend fun saveWhitelistedKeywords(keywords: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[WHITELISTED_KEYWORDS] = keywords
        }
    }

    suspend fun saveDeletionProtection(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[DELETION_PROTECTION] = enabled.toString()
        }
    }

    val motivationVideos: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[MOTIVATION_VIDEOS] ?: emptySet()
    }

    val motivationChannels: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[MOTIVATION_CHANNELS] ?: emptySet()
    }

    // Reactive version of loadMotivationConfig — reads the JSON key so gallery videos,
    // phrases and duration are included. Falls back to the legacy URL-only keys for
    // installs that haven't been migrated yet.
    val motivationConfigFlow: Flow<MotivationConfig> = context.dataStore.data.map { prefs ->
        val json = prefs[MOTIVATION_CONFIG_JSON]
        if (json == null) {
            val legacyVideos = prefs[MOTIVATION_VIDEOS] ?: emptySet()
            val legacyChannels = prefs[MOTIVATION_CHANNELS] ?: emptySet()
            return@map MotivationConfig(
                videos = legacyVideos.map { MotivationItem(url = it) },
                channels = legacyChannels.map { MotivationItem(url = it) }
            )
        }
        try {
            val obj = org.json.JSONObject(json)
            val videosArr = obj.optJSONArray("videos") ?: org.json.JSONArray()
            val channelsArr = obj.optJSONArray("channels") ?: org.json.JSONArray()
            val galleryArr = obj.optJSONArray("galleryVideos") ?: org.json.JSONArray()
            val phrasesArr = obj.optJSONArray("phrases") ?: org.json.JSONArray()
            val duration = obj.optInt("duration", 10)
            MotivationConfig(
                videos = (0 until videosArr.length()).map { i ->
                    val v = videosArr.getJSONObject(i)
                    MotivationItem(url = v.getString("url"), label = v.optString("label").ifEmpty { null })
                },
                channels = (0 until channelsArr.length()).map { i ->
                    val c = channelsArr.getJSONObject(i)
                    MotivationItem(url = c.getString("url"), label = c.optString("label").ifEmpty { null })
                },
                galleryVideos = (0 until galleryArr.length()).map { i ->
                    val g = galleryArr.getJSONObject(i)
                    MotivationItem(url = g.getString("url"), label = g.optString("label").ifEmpty { null })
                },
                phrases = (0 until phrasesArr.length()).map { i -> phrasesArr.getString(i) },
                duration = duration
            )
        } catch (e: Exception) {
            MotivationConfig()
        }
    }

    suspend fun saveMotivationVideos(videos: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[MOTIVATION_VIDEOS] = videos
        }
    }

    suspend fun saveMotivationChannels(channels: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[MOTIVATION_CHANNELS] = channels
        }
    }

    // Each entry is stored as "URL::FILEPATH"
    val downloadedVideos: Flow<Map<String, String>> = context.dataStore.data.map { prefs ->
        (prefs[DOWNLOADED_VIDEOS] ?: emptySet()).mapNotNull { entry ->
            val idx = entry.indexOf("::")
            if (idx > 0) entry.substring(0, idx) to entry.substring(idx + 2) else null
        }.toMap()
    }

    suspend fun saveDownloadedVideo(videoUrl: String, filePath: String) {
        context.dataStore.edit { prefs ->
            val updated = (prefs[DOWNLOADED_VIDEOS] ?: emptySet())
                .filter { !it.startsWith("$videoUrl::") }
                .toMutableSet()
            updated.add("$videoUrl::$filePath")
            prefs[DOWNLOADED_VIDEOS] = updated
        }
    }

    suspend fun removeDownloadedVideo(videoUrl: String) {
        context.dataStore.edit { prefs ->
            prefs[DOWNLOADED_VIDEOS] = (prefs[DOWNLOADED_VIDEOS] ?: emptySet())
                .filter { !it.startsWith("$videoUrl::") }.toSet()
        }
    }

    // ================== Pending Changes (JSON) ==================

    suspend fun loadPendingChanges(): List<com.focusapp.blocker.data.PendingChange> {
        val prefs = context.dataStore.data.first()
        val json = prefs[PENDING_CHANGES_JSON] ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                com.focusapp.blocker.data.PendingChange(
                    id = obj.getString("id"),
                    type = obj.getString("type"),
                    value = if (obj.isNull("value")) null else obj.getString("value"),
                    createdAt = obj.getString("createdAt"),
                    scheduledFor = obj.getString("scheduledFor")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun savePendingChanges(changes: List<com.focusapp.blocker.data.PendingChange>) {
        val arr = org.json.JSONArray()
        changes.forEach { change ->
            arr.put(org.json.JSONObject().apply {
                put("id", change.id)
                put("type", change.type)
                if (change.value != null) put("value", change.value) else put("value", org.json.JSONObject.NULL)
                put("createdAt", change.createdAt)
                put("scheduledFor", change.scheduledFor)
            })
        }
        context.dataStore.edit { prefs -> prefs[PENDING_CHANGES_JSON] = arr.toString() }
    }

    // ================== Motivation Config (JSON, includes labels) ==================

    suspend fun loadMotivationConfig(): com.focusapp.blocker.data.MotivationConfig {
        val prefs = context.dataStore.data.first()
        val json = prefs[MOTIVATION_CONFIG_JSON]

        // Migrate from legacy URL-only storage if JSON not yet written
        if (json == null) {
            val legacyVideos = prefs[MOTIVATION_VIDEOS] ?: emptySet()
            val legacyChannels = prefs[MOTIVATION_CHANNELS] ?: emptySet()
            if (legacyVideos.isNotEmpty() || legacyChannels.isNotEmpty()) {
                val config = com.focusapp.blocker.data.MotivationConfig(
                    videos = legacyVideos.map { com.focusapp.blocker.data.MotivationItem(url = it) },
                    channels = legacyChannels.map { com.focusapp.blocker.data.MotivationItem(url = it) }
                )
                saveMotivationConfig(config)
                return config
            }
            return com.focusapp.blocker.data.MotivationConfig()
        }

        return try {
            val obj = org.json.JSONObject(json)
            val videosArr = obj.optJSONArray("videos") ?: org.json.JSONArray()
            val channelsArr = obj.optJSONArray("channels") ?: org.json.JSONArray()
            val galleryArr = obj.optJSONArray("galleryVideos") ?: org.json.JSONArray()
            val phrasesArr = obj.optJSONArray("phrases") ?: org.json.JSONArray()
            val duration = obj.optInt("duration", 10)
            com.focusapp.blocker.data.MotivationConfig(
                videos = (0 until videosArr.length()).map { i ->
                    val v = videosArr.getJSONObject(i)
                    com.focusapp.blocker.data.MotivationItem(
                        url = v.getString("url"),
                        label = if (v.isNull("label")) null else v.optString("label")
                    )
                },
                channels = (0 until channelsArr.length()).map { i ->
                    val c = channelsArr.getJSONObject(i)
                    com.focusapp.blocker.data.MotivationItem(
                        url = c.getString("url"),
                        label = if (c.isNull("label")) null else c.optString("label")
                    )
                },
                galleryVideos = (0 until galleryArr.length()).map { i ->
                    val g = galleryArr.getJSONObject(i)
                    com.focusapp.blocker.data.MotivationItem(
                        url = g.getString("url"),
                        label = if (g.isNull("label")) null else g.optString("label")
                    )
                },
                phrases = (0 until phrasesArr.length()).map { i -> phrasesArr.getString(i) },
                duration = duration
            )
        } catch (e: Exception) {
            com.focusapp.blocker.data.MotivationConfig()
        }
    }

    suspend fun saveMotivationConfig(config: com.focusapp.blocker.data.MotivationConfig) {
        val obj = org.json.JSONObject().apply {
            put("duration", config.duration)
            put("videos", org.json.JSONArray().also { arr ->
                config.videos.forEach { item ->
                    arr.put(org.json.JSONObject().apply {
                        put("url", item.url)
                        put("label", item.label ?: org.json.JSONObject.NULL)
                    })
                }
            })
            put("channels", org.json.JSONArray().also { arr ->
                config.channels.forEach { item ->
                    arr.put(org.json.JSONObject().apply {
                        put("url", item.url)
                        put("label", item.label ?: org.json.JSONObject.NULL)
                    })
                }
            })
            put("galleryVideos", org.json.JSONArray().also { arr ->
                config.galleryVideos.forEach { item ->
                    arr.put(org.json.JSONObject().apply {
                        put("url", item.url)
                        put("label", item.label ?: org.json.JSONObject.NULL)
                    })
                }
            })
            put("phrases", org.json.JSONArray().also { arr ->
                config.phrases.forEach { phrase -> arr.put(phrase) }
            })
        }
        context.dataStore.edit { prefs -> prefs[MOTIVATION_CONFIG_JSON] = obj.toString() }
    }

    // ================== Adult Blocking ==================

    // 0 = off, 1 = sites only, 2 = sites + keywords (Strict)
    // Migrates from old boolean key: true → 2, false → 0
    val adultBlockingLevel: Flow<Int> = context.dataStore.data.map { preferences ->
        val stored = preferences[ADULT_BLOCKING_LEVEL]?.toIntOrNull()
        when {
            stored != null -> stored
            preferences[ADULT_BLOCKING_ENABLED]?.toBooleanStrictOrNull() == true -> 2
            else -> 0
        }
    }

    suspend fun saveAdultBlockingLevel(level: Int) {
        context.dataStore.edit { preferences ->
            preferences[ADULT_BLOCKING_LEVEL] = level.toString()
        }
    }

    // ================== YouTube Shorts & Instagram Reels ==================

    val blockYoutubeShorts: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[BLOCK_YOUTUBE_SHORTS]?.toBooleanStrictOrNull() ?: false
    }

    val blockInstagramReels: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[BLOCK_INSTAGRAM_REELS]?.toBooleanStrictOrNull() ?: false
    }

    suspend fun saveBlockYoutubeShorts(enabled: Boolean) {
        context.dataStore.edit { it[BLOCK_YOUTUBE_SHORTS] = enabled.toString() }
    }

    suspend fun saveBlockInstagramReels(enabled: Boolean) {
        context.dataStore.edit { it[BLOCK_INSTAGRAM_REELS] = enabled.toString() }
    }

    // ================== 24h Lock ==================

    val lockEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[LOCK_ENABLED]?.toBooleanStrictOrNull() ?: false
    }

    val durationLocked: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[DURATION_LOCKED]?.toBooleanStrictOrNull() ?: false
    }

    suspend fun saveLockEnabled(enabled: Boolean) {
        context.dataStore.edit { it[LOCK_ENABLED] = enabled.toString() }
    }

    suspend fun saveDurationLocked(locked: Boolean) {
        context.dataStore.edit { it[DURATION_LOCKED] = locked.toString() }
    }

    val contentLocked: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[CONTENT_LOCKED]?.toBooleanStrictOrNull() ?: false
    }

    suspend fun saveContentLocked(locked: Boolean) {
        context.dataStore.edit { it[CONTENT_LOCKED] = locked.toString() }
    }

    // ================== Behavior Settings ==================

    val settingsProtectionLevel: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[SETTINGS_PROTECTION_LEVEL]?.toIntOrNull() ?: 0
    }

    val motivationOnBlock: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[MOTIVATION_ON_BLOCK]?.toBooleanStrictOrNull() ?: false
    }

    val motivationOnSettings: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[MOTIVATION_ON_SETTINGS]?.toBooleanStrictOrNull() ?: false
    }

    val hideAppIcon: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[HIDE_APP_ICON]?.toBooleanStrictOrNull() ?: false
    }

    suspend fun saveSettingsProtectionLevel(level: Int) {
        context.dataStore.edit { it[SETTINGS_PROTECTION_LEVEL] = level.toString() }
    }

    suspend fun saveMotivationOnBlock(enabled: Boolean) {
        context.dataStore.edit { it[MOTIVATION_ON_BLOCK] = enabled.toString() }
    }

    suspend fun saveMotivationOnSettings(enabled: Boolean) {
        context.dataStore.edit { it[MOTIVATION_ON_SETTINGS] = enabled.toString() }
    }

    suspend fun saveHideAppIcon(enabled: Boolean) {
        context.dataStore.edit { it[HIDE_APP_ICON] = enabled.toString() }
    }

    suspend fun loadCachedConfig(): CachedConfig {
        val prefs = context.dataStore.data.first()
        return CachedConfig(
            blockedPackages = prefs[BLOCKED_PACKAGES] ?: emptySet(),
            blockedKeywords = prefs[BLOCKED_KEYWORDS] ?: emptySet(),
            blockedWebsites = prefs[BLOCKED_WEBSITES] ?: emptySet(),
            whitelistedPackages = prefs[WHITELISTED_PACKAGES] ?: emptySet(),
            whitelistedWebsites = prefs[WHITELISTED_WEBSITES] ?: emptySet(),
            whitelistedKeywords = prefs[WHITELISTED_KEYWORDS] ?: emptySet(),
            deletionProtection = prefs[DELETION_PROTECTION]?.toBooleanStrictOrNull() ?: false
        )
    }

    data class CachedConfig(
        val blockedPackages: Set<String>,
        val blockedKeywords: Set<String>,
        val blockedWebsites: Set<String>,
        val whitelistedPackages: Set<String>,
        val whitelistedWebsites: Set<String>,
        val whitelistedKeywords: Set<String>,
        val deletionProtection: Boolean
    )
}
