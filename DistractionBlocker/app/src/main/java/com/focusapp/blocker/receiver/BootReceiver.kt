package com.focusapp.blocker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.focusapp.blocker.service.FocusBlockerForegroundService

/**
 * Starts the foreground service on device boot so that blocking resumes
 * automatically without the user needing to open the app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            FocusBlockerForegroundService.startService(context)
        }
    }
}
