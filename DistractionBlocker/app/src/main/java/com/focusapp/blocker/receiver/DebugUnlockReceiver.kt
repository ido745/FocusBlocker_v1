package com.focusapp.blocker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.focusapp.blocker.data.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Developer-only escape hatch. Trigger via adb:
 *   adb shell am broadcast -n com.focusapp.blocker/.receiver.DebugUnlockReceiver
 *
 * Effect: disables the 24-hour lock and removes Instagram from the blocked-packages
 * list so you can open it and test Reels blocking. Re-add Instagram and re-enable
 * the lock from the app UI afterwards.
 *
 * NOT exported — only reachable via adb shell (requires physical device access).
 */
class DebugUnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.w("DebugUnlock", "Debug unlock triggered — disabling lock + unblocking Instagram")
        val prefs = PreferencesManager(context)
        CoroutineScope(Dispatchers.IO).launch {
            prefs.saveLockEnabled(false)
            prefs.saveContentLocked(false)
            prefs.saveDurationLocked(false)
            val current = prefs.loadCachedConfig().blockedPackages
            val updated = current.filterNot {
                it == "com.instagram.android" || it == "com.instagram.lite"
            }.toSet()
            prefs.saveBlockedPackages(updated)
        }
        Toast.makeText(context, "Debug: lock off, Instagram unblocked", Toast.LENGTH_LONG).show()
    }
}
