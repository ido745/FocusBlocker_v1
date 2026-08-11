package com.focusapp.blocker.receiver

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.focusapp.blocker.data.PreferencesManager
import com.focusapp.blocker.service.BlockingAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PendingChangesReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_APPLY = "com.focusapp.blocker.PENDING_CHANGE_APPLY"
        const val EXTRA_CHANGE_ID = "extra_change_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val changeId = intent.getStringExtra(EXTRA_CHANGE_ID) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                val prefs = PreferencesManager(context)
                val changes = prefs.loadPendingChanges()
                val change = changes.firstOrNull { it.id == changeId } ?: return@launch

                when (change.type) {
                    "remove_blocked_package" -> {
                        val v = change.value ?: return@launch
                        prefs.saveBlockedPackages(prefs.blockedPackages.first() - v)
                    }
                    "remove_blocked_keyword" -> {
                        val v = change.value ?: return@launch
                        prefs.saveBlockedKeywords(prefs.blockedKeywords.first() - v)
                    }
                    "remove_blocked_website" -> {
                        val v = change.value ?: return@launch
                        prefs.saveBlockedWebsites(prefs.blockedWebsites.first() - v)
                    }
                    "add_whitelisted_package" -> {
                        val v = change.value ?: return@launch
                        prefs.saveWhitelistedPackages(prefs.whitelistedPackages.first() + v)
                    }
                    "add_whitelisted_website" -> {
                        val v = change.value ?: return@launch
                        prefs.saveWhitelistedWebsites(prefs.whitelistedWebsites.first() + v)
                    }
                    "disable_deletion_protection" -> {
                        prefs.saveDeletionProtection(false)
                        deactivateDeviceAdmin(context)
                    }
                    "disable_adult_blocking" -> {
                        prefs.saveAdultBlockingLevel(0)
                        BlockingAccessibilityService.adultBlockingLevel = 0
                    }
                    "lower_adult_blocking" -> {
                        // No `?: 0` fallback here: defaulting a malformed value to 0 would
                        // silently disable adult blocking entirely instead of stepping it
                        // down. On a bad value, leave the setting untouched.
                        val level = change.value?.toIntOrNull()
                        if (level != null) {
                            prefs.saveAdultBlockingLevel(level)
                            BlockingAccessibilityService.adultBlockingLevel = level
                        }
                    }
                    "disable_lock" -> {
                        prefs.saveLockEnabled(false)
                    }
                    "unlock_duration" -> {
                        prefs.saveDurationLocked(false)
                    }
                    "unlock_content" -> {
                        prefs.saveContentLocked(false)
                    }
                    "disable_motivation_on_block" -> {
                        prefs.saveMotivationOnBlock(false)
                        BlockingAccessibilityService.motivationOnBlock = false
                    }
                    "disable_motivation_on_settings" -> {
                        prefs.saveMotivationOnSettings(false)
                        BlockingAccessibilityService.motivationOnSettings = false
                    }
                    "lower_settings_protection" -> {
                        val level = change.value?.toIntOrNull() ?: 0
                        prefs.saveSettingsProtectionLevel(level)
                        BlockingAccessibilityService.settingsProtectionLevel = level
                    }
                    "show_app_icon" -> {
                        prefs.saveHideAppIcon(false)
                        context.packageManager.setComponentEnabledSetting(
                            ComponentName(context, "${context.packageName}.LauncherActivity"),
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            PackageManager.DONT_KILL_APP
                        )
                    }
                    "disable_youtube_shorts_block" -> {
                        prefs.saveBlockYoutubeShorts(false)
                        BlockingAccessibilityService.blockYoutubeShorts = false
                    }
                    "disable_instagram_reels_block" -> {
                        prefs.saveBlockInstagramReels(false)
                        BlockingAccessibilityService.blockInstagramReels = false
                    }
                    else -> Log.w("PendingChangesReceiver", "Unknown change type: ${change.type}")
                }

                prefs.savePendingChanges(changes.filter { it.id != changeId })
            } catch (e: Exception) {
                Log.e("PendingChangesReceiver", "Error applying change $changeId", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun deactivateDeviceAdmin(context: Context) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, FocusDeviceAdminReceiver::class.java)
            if (dpm.isAdminActive(admin)) dpm.removeActiveAdmin(admin)
        } catch (e: Exception) {
            Log.e("PendingChangesReceiver", "Error deactivating admin", e)
        }
    }
}
