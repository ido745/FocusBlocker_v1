package com.focusapp.blocker.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.focusapp.blocker.data.PreferencesManager
import com.focusapp.blocker.service.FocusBlockerForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            FocusBlockerForegroundService.startService(context)
            reschedulePendingAlarms(context)
        }
    }

    private fun reschedulePendingAlarms(context: Context) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                val prefs = PreferencesManager(context)
                val changes = prefs.loadPendingChanges()
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val now = System.currentTimeMillis()

                for (change in changes) {
                    val triggerAt = Instant.parse(change.scheduledFor).toEpochMilli()
                    if (triggerAt <= now) continue // Past due — will be applied by AuthViewModel on next launch

                    val alarmIntent = Intent(context, PendingChangesReceiver::class.java).apply {
                        action = PendingChangesReceiver.ACTION_APPLY
                        putExtra(PendingChangesReceiver.EXTRA_CHANGE_ID, change.id)
                    }
                    val pi = PendingIntent.getBroadcast(
                        context,
                        change.id.hashCode(),
                        alarmIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    scheduleAlarm(am, triggerAt, pi)
                    Log.d("BootReceiver", "Rescheduled alarm for change ${change.id}")
                }
            } catch (e: Exception) {
                Log.e("BootReceiver", "Error rescheduling alarms", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun scheduleAlarm(am: AlarmManager, triggerAt: Long, pi: PendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }
}
