package org.openwebdav.messenger.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.openwebdav.messenger.R

/**
 * Foreground service that polls the WebDAV disk at sub-15-minute intervals.
 *
 * Android enforces a persistent notification for foreground services — the user accepts this as the
 * explicit cost of faster message delivery. The poll loop calls [SyncRunner.current().runOnce()], the
 * same seam the [SyncWorker] uses, so sync logic stays in one place.
 *
 * Lifecycle:
 *  - [start] → [onStartCommand] calls [startForeground] with a persistent notification, then begins a
 *    coroutine loop polling at the given interval.
 *  - [stop] → [onDestroy] cancels the loop and removes the notification.
 *  - A force-stop kills the process; [START_STICKY] restarts the service with a null intent (default
 *    interval). The companion WorkManager poll is cancelled while this service runs.
 */
class FastPollService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        warnIfNotificationsBlocked()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // When the intent is null (START_STICKY restart after process kill), read the persisted
        // effective interval so the user-configured interval (clamped to community floor) survives.
        val intervalSeconds =
            intent?.getLongExtra(EXTRA_INTERVAL_SECONDS, -1L)
                ?.takeIf { it > 0 }
                ?: FastPollManager.effectiveIntervalSeconds(this)

        val notification = buildNotification(intervalSeconds)
        startForeground(NOTIFICATION_ID, notification)

        // Restart the loop if the interval changed (cancel old, start new)
        pollingJob?.cancel()
        pollingJob = startPolling(intervalSeconds)

        return START_STICKY
    }

    override fun onDestroy() {
        pollingJob?.cancel()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.fast_poll_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.fast_poll_channel_description)
                setShowBadge(false)
            }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    /**
     * On Android 13+ the user may have denied notification permission at the OS level.
     * The permission dialog must be shown from an Activity (future settings UI); here we
     * log a warning so the issue is visible in logcat and the service still runs —
     * foreground services are exempt from the user-facing notification block on Android 14+,
     * but the notification may be suppressed on Android 13.
     */
    private fun warnIfNotificationsBlocked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                Log.w(
                    TAG,
                    "Notification permission not granted. The foreground service runs, " +
                        "but the notification may be hidden. Grant the permission in " +
                        "Settings > Apps > Open WebDAV Messenger > Notifications.",
                )
            }
        }
    }

    private fun buildNotification(intervalSeconds: Long): Notification {
        val readable =
            org.openwebdav.messenger.ui.settings.UserSettings.formatPollInterval(intervalSeconds.toInt())
        val contentText = getString(R.string.fast_poll_notification_text, readable)
        val openIntent =
            PendingIntent.getActivity(
                this,
                0,
                packageManager.getLaunchIntentForPackage(packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.fast_poll_notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_sync_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .build()
    }

    private fun startPolling(intervalSeconds: Long): Job =
        scope.launch {
            val intervalMillis = intervalSeconds * 1000L
            while (isActive) {
                try {
                    val outcome = SyncRunner.current().runOnce()
                    if (outcome.newCount > 0) {
                        showMessageNotification(outcome.newCount)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Poll cycle failed; resuming after delay", e)
                }
                delay(intervalMillis)
            }
        }

    private fun showMessageNotification(newCount: Int) {
        SyncNotifier.showMessages(this, newCount)
    }

    companion object {
        private const val TAG = "FastPollService"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "owdm.fast_poll"
        const val EXTRA_INTERVAL_SECONDS = "owdm.fastpoll.interval_seconds"

        /** Start the foreground service with the given poll interval (seconds). */
        fun start(
            context: Context,
            intervalSeconds: Long,
        ) {
            val intent =
                Intent(context, FastPollService::class.java).apply {
                    putExtra(EXTRA_INTERVAL_SECONDS, intervalSeconds)
                }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Stop the foreground service, removing its notification. */
        fun stop(context: Context) {
            context.stopService(Intent(context, FastPollService::class.java))
        }
    }
}
