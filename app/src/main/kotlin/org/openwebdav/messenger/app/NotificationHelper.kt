package org.openwebdav.messenger.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Manages Android notifications for new messages arriving during background poll cycles.
 *
 * Creates the "Messages" notification channel at app start and exposes a single entry point
 * [showCycleNotification] that summarizes new messages from a completed poll cycle.
 *
 * **Notification policy:**
 * - Shows "N new messages in ChatName" when new messages arrive during a poll cycle.
 * - Multi-chat grouping (per-chat notifications, summary for 4+ chats) is deferred — currently
 *   posts one notification per chat via [showCycleNotification].

 * **Permission handling:** if the `POST_NOTIFICATIONS` permission is denied (Android 13+),
 * notifications are silently skipped — never crash, never prompt.

 * **Grouping:** all notifications use the group key [GROUP_KEY] for visual grouping on Android.
 * A summary notification structure is reserved but not yet wired (multi-chat is deferred).
 */
internal object NotificationHelper {
    const val CHANNEL_ID = "owdm_messages"
    private const val CHANNEL_NAME = "Messages"
    private const val GROUP_KEY = "owdm_messages"
    private const val SUMMARY_ID = 0

    /** Create the notification channel. Idempotent — safe to call multiple times. */
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "New message notifications"
                    setShowBadge(true)
                }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Show notification(s) for a completed poll cycle that had new messages.
     *
     * Called from the poll path after [org.openwebdav.messenger.sync.CycleOutcome.newCount] > 0.
     * The [newCount] and [chatName] describe how many new messages arrived in which chat.
     *
     * **Best-effort:** if the notification permission is denied, this is a no-op. Never throws.
     */
    @SuppressLint("MissingPermission")
    fun showCycleNotification(
        context: Context,
        chatName: String,
        newCount: Int,
    ) {
        if (!areNotificationsEnabled(context)) return
        if (newCount <= 0) return

        val title = chatName
        val text =
            if (newCount == 1) {
                "1 new message"
            } else {
                "$newCount new messages"
            }

        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                chatName.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setGroup(GROUP_KEY)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

        val manager = NotificationManagerCompat.from(context)
        manager.notify(chatName.hashCode(), notification)
    }

    /** Check whether notifications are allowed at the OS level (Android 13+) or channel level. */
    private fun areNotificationsEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}
