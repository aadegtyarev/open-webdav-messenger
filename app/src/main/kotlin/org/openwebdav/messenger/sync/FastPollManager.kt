package org.openwebdav.messenger.sync

import android.content.Context
import androidx.work.WorkManager

/**
 * Coordinates the fast-poll foreground service and the WorkManager background poll.
 *
 * When fast polling is enabled:
 *  1. The WorkManager periodic poll is cancelled (the foreground service replaces it).
 *  2. [FastPollService] is started with the user-configured interval.
 *
 * When fast polling is disabled:
 *  1. The foreground service is stopped.
 *  2. The WorkManager periodic poll is re-scheduled at its default interval.
 *
 * The toggle state persists in [SharedPreferences] so it survives process death; the [Application]
 * class restores it on next start. The interval is also persisted so the user's choice is kept.
 */
object FastPollManager {
    private const val PREFS_NAME = "owdm.fastpoll"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_INTERVAL_MINUTES = "interval_minutes"

    /** Whether fast polling is currently enabled. */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    /** The configured fast-poll interval in minutes, or [DEFAULT_INTERVAL] if never set. */
    fun intervalMinutes(context: Context): Long = prefs(context).getLong(KEY_INTERVAL_MINUTES, DEFAULT_INTERVAL)

    /**
     * Enable fast polling: persist the toggle + interval, cancel WorkManager, start the
     * foreground service. The user MUST have opted in — this is never auto-enabled.
     */
    fun enable(
        context: Context,
        workManager: WorkManager,
        intervalMinutes: Long = DEFAULT_INTERVAL,
    ) {
        prefs(context)
            .edit()
            .putBoolean(KEY_ENABLED, true)
            .putLong(KEY_INTERVAL_MINUTES, intervalMinutes)
            .apply()
        SyncScheduler.cancel(workManager)
        FastPollService.start(context, intervalMinutes)
    }

    /**
     * Disable fast polling: persist the toggle off, stop the foreground service, re-schedule
     * the WorkManager periodic poll at its default interval.
     */
    fun disable(
        context: Context,
        workManager: WorkManager,
    ) {
        prefs(context)
            .edit()
            .putBoolean(KEY_ENABLED, false)
            .apply()
        FastPollService.stop(context)
        SyncScheduler.schedule(workManager)
    }

    /**
     * Restore the fast-poll state on app start. If the user had fast polling enabled before the
     * process was killed (crash / force-stop / low-memory), restart the service. Must be called
     * with a [WorkManager] instance so the WorkManager poll is cancelled while the service runs.
     */
    fun restoreIfEnabled(
        context: Context,
        workManager: WorkManager,
    ) {
        if (isEnabled(context)) {
            val interval = intervalMinutes(context)
            SyncScheduler.cancel(workManager)
            FastPollService.start(context, interval)
        }
    }

    /** The default fast-poll interval: 5 minutes. The single source — [FastPollService] reads from here. */
    const val DEFAULT_INTERVAL = 5L

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
