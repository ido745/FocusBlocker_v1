package com.focusapp.blocker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.focusapp.blocker.MainActivity
import com.focusapp.blocker.R

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
            context.startForegroundService(intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, FocusBlockerForegroundService::class.java)
            context.stopService(intent)
        }

        /**
         * Asks the foreground service to launch MainActivity.
         * MIUI/Poco allows startActivity from a foreground service (visible notification)
         * but blocks it from plain background services and accessibility services.
         */
        fun launchMainActivity(context: Context) {
            val intent = Intent(context, FocusBlockerForegroundService::class.java).apply {
                action = ACTION_LAUNCH_MAIN
            }
            context.startForegroundService(intent)
        }

        /**
         * Brings MainActivity to the front and delivers a video URL for auto-play.
         * The MainActivity handles ACTION_LAUNCH_MOTIVATION in onNewIntent to open
         * the motivation player.
         */
        fun launchMotivation(context: Context, videoUrl: String) {
            val intent = Intent(context, FocusBlockerForegroundService::class.java).apply {
                action = ACTION_LAUNCH_MOTIVATION
                putExtra(EXTRA_VIDEO_URL, videoUrl)
            }
            context.startForegroundService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
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
