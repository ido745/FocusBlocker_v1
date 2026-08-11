package com.focusapp.blocker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.focusapp.blocker.MainActivity
import com.focusapp.blocker.R
import com.focusapp.blocker.receiver.FocusDeviceAdminReceiver

/**
 * A persistent foreground service that keeps the app process alive.
 * This prevents Android from killing the accessibility service when the user
 * closes the main app. The notification shows "Focus Blocker is active."
 */
class FocusBlockerForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "focus_blocker_channel"
        private const val NOTIFICATION_ID = 1001

        private const val ACTION_LAUNCH_MAIN = "com.focusapp.blocker.LAUNCH_MAIN"
        const val ACTION_LAUNCH_MOTIVATION = "com.focusapp.blocker.LAUNCH_MOTIVATION"
        const val EXTRA_VIDEO_URL = "extra_video_url"

        fun startService(context: Context) {
            val intent = Intent(context, FocusBlockerForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, FocusBlockerForegroundService::class.java)
            context.stopService(intent)
        }

        fun launchMainActivity(context: Context) {
            val intent = Intent(context, FocusBlockerForegroundService::class.java).apply {
                action = ACTION_LAUNCH_MAIN
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun launchMotivation(context: Context, videoUrl: String) {
            val intent = Intent(context, FocusBlockerForegroundService::class.java).apply {
                action = ACTION_LAUNCH_MOTIVATION
                putExtra(EXTRA_VIDEO_URL, videoUrl)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            restoreAccessibilityIfMissing()
            nagIfAccessibilityMissing()
            watchdogHandler.postDelayed(this, 1_000)
        }
    }

    private var lastNagMs = 0L

    private fun accessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(
            packageName, "com.focusapp.blocker.service.BlockingAccessibilityService"
        ).flattenToString()
        val current = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        return current.split(":").any { it.equals(expected, ignoreCase = true) }
    }

    /**
     * Self-heal for devices that are not Device Owner, where we cannot write the setting
     * back ourselves. If the guard was expected to be running and has been switched off,
     * drag the user straight back into the app, once a second, indefinitely.
     *
     * This is the layer that makes winning the race worthless: even a successful toggle
     * buys nothing, because the app is back in the foreground before the settings page is.
     */
    private fun nagIfAccessibilityMissing() {
        try {
            val expected = getSharedPreferences("focus_guard", Context.MODE_PRIVATE)
                .getBoolean("guard_expected", false)
            if (!expected || accessibilityServiceEnabled()) return
            val now = System.currentTimeMillis()
            if (now - lastNagMs < 1_000) return
            lastNagMs = now
            android.util.Log.w("FocusFGService", "⚠️ Accessibility service disabled — recalling app")
            launchMainActivity(this)
        } catch (e: Exception) {
            android.util.Log.e("FocusFGService", "Nag failed: ${e.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            android.util.Log.e("FocusFGService", "startForeground failed: ${e.message}")
            stopSelf()
            return
        }
        watchdogHandler.postDelayed(watchdogRunnable, 2_000)
    }

    override fun onDestroy() {
        super.onDestroy()
        watchdogHandler.removeCallbacks(watchdogRunnable)
    }

    private fun restoreAccessibilityIfMissing() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (!dpm.isDeviceOwnerApp(packageName)) return
            val serviceFlat = ComponentName(packageName, "com.focusapp.blocker.service.BlockingAccessibilityService").flattenToString()
            val current = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            if (!current.contains(serviceFlat)) {
                val admin = ComponentName(this, FocusDeviceAdminReceiver::class.java)
                val services = current.split(":").filter { it.isNotBlank() }.toMutableSet()
                services.add(serviceFlat)
                dpm.setSecureSetting(admin, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services.joinToString(":"))
                dpm.setSecureSetting(admin, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
                android.util.Log.w("FocusFGService", "🔄 Watchdog restored accessibility service")
            }
        } catch (e: Exception) {
            android.util.Log.e("FocusFGService", "Watchdog restore failed: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.w("FocusFGService", "🔵 onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_LAUNCH_MAIN -> {
                android.util.Log.w("FocusFGService", "🔵 STEP 3: ACTION_LAUNCH_MAIN received, moving task to front")
                try {
                    val am = getSystemService(android.app.ActivityManager::class.java)
                    val moved = am.appTasks.firstOrNull { task ->
                        task.taskInfo.baseIntent?.component?.packageName == packageName
                    }?.also { it.moveToFront() }

                    if (moved != null) {
                        android.util.Log.w("FocusFGService", "🔵 STEP 3: moveToFront succeeded")
                    } else {
                        android.util.Log.w("FocusFGService", "🔵 STEP 3: no existing task found, falling back to startActivity")
                        val launchIntent = Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        startActivity(launchIntent)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("FocusFGService", "🔵 STEP 3: THREW: ${e::class.simpleName}: ${e.message}", e)
                }
            }
            ACTION_LAUNCH_MOTIVATION -> {
                val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL) ?: ""
                android.util.Log.w("FocusFGService", "🎬 ACTION_LAUNCH_MOTIVATION url=$videoUrl")
                try {
                    val am = getSystemService(android.app.ActivityManager::class.java)
                    val existingTask = am.appTasks.firstOrNull { task ->
                        task.taskInfo.baseIntent?.component?.packageName == packageName
                    }
                    if (existingTask != null) {
                        existingTask.moveToFront()
                        // Deliver the video URL via a new intent to the activity
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            try {
                                val deliverIntent = Intent(this, MainActivity::class.java).apply {
                                    action = ACTION_LAUNCH_MOTIVATION
                                    putExtra(EXTRA_VIDEO_URL, videoUrl)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                }
                                startActivity(deliverIntent)
                            } catch (e: Exception) {
                                android.util.Log.e("FocusFGService", "Failed to deliver motivation intent", e)
                            }
                        }, 200)
                    } else {
                        val launchIntent = Intent(this, MainActivity::class.java).apply {
                            action = ACTION_LAUNCH_MOTIVATION
                            putExtra(EXTRA_VIDEO_URL, videoUrl)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        startActivity(launchIntent)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("FocusFGService", "Failed to launch motivation", e)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Focus Blocker Active")
            .setContentText("Blocking distractions in the background")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Focus Blocker Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps Focus Blocker running in the background"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
