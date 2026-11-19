package com.zippush.app.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.zippush.app.R

/**
 * Foreground service whose ONLY job is to keep the process alive while
 * the runner's push is uploading (v0.2.8). When the user backgrounds
 * the app mid-push, Android would otherwise reap the process within
 * seconds and the push would die. A dataSync foreground service raises
 * that ceiling for the (short) life of the upload.
 *
 * Deliberately NOT the v0.3.x PushService:
 *  - it runs NO upload logic of its own — the runner owns the job;
 *  - it observes the runner and stops itself the moment the push ends
 *    (success or failure);
 *  - no queue, no retries, no progress re-broadcasting.
 *
 * The notification is silent and channel-only; on Android 13+ without
 * the notification permission it is simply not shown — the push itself
 * is unaffected (the service still runs in the foreground).
 */
class PushService : Service() {

    companion object {
        private const val CHANNEL_ID = "zippush_push"
        private const val NOTIFICATION_ID = 42

        /**
         * Starts the service in the foreground while a push is running.
         * Safe to call repeatedly — a running service ignores restarts.
         */
        fun start(context: Context) {
            val intent = Intent(context, PushService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PushService::class.java))
        }
    }

    private var observer: PushRunner.Observer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The service exists only while a push is in flight; go to the
        // foreground FIRST (startForegroundService contract: always call
        // startForeground, even about to stop).
        startForeground(NOTIFICATION_ID, buildNotification())
        if (!PushRunner.isBusy()) {
            // The push already finished between the start request and
            // here — nothing to protect.
            stopSelf()
            return START_NOT_STICKY
        }
        if (observer == null) {
            observer = PushRunner.Observer { st ->
                if (st !is PushRunner.State.Running) {
                    // Done (or idle) — the upload no longer needs us.
                    stopSelf()
                }
            }
            PushRunner.addObserver(observer!!)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observer?.let { PushRunner.removeObserver(it) }
        observer = null
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.push_notif_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.push_notif_channel_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val title = getString(R.string.push_notif_active)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_push)
            .setContentTitle(title)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
