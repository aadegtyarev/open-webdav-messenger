package org.openwebdav.messenger.sync

import android.content.Context
import androidx.work.WorkManager

/**
 * Coordinates the fast-poll foreground service and the WorkManager background poll.
 *
 * When fast polling is enabled:
 *  1. The WorkManager periodic poll is cancelled (the foreground service replaces it).
 *  2. [FastPollService] is started with the effective interval (user preference clamped to
 *     community floor and fast-poll platform floor).
 *
 * When fast polling is disabled:
 *  1. The foreground service is stopped.
 *  2. The WorkManager periodic poll is re-scheduled at its effective interval.
 *
 * The toggle state persists in [SharedPreferences] so it survives process death; the [Application]
 * class restores it on next start. The interval is also persisted so the user's choice is kept.
 */
object FastPollManager {
    private const val PREFS_NAME = "owdm.fastpoll"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_INTERVAL_SECONDS = "interval_seconds"

    /** Minimum reasonable poll interval for foreground service: 15 seconds (Android allows sub-15-min foreground). */
    const val PLATFORM_FLOOR_SECONDS = 15L

    /** Whether fast polling is currently enabled. */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    /** The configured fast-poll interval in seconds (user's raw preference, not clamped). */
    fun intervalSeconds(context: Context): Long = prefs(context).getLong(KEY_INTERVAL_SECONDS, DEFAULT_INTERVAL)

    /**
     * The effective fast-poll interval: `max(userPref, communityFloor, platformFloor)`.
     * The community floor is read from [org.openwebdav.messenger.ui.settings.UserSettings.communityMinPollSeconds].
     * If UserSettings is not initialized (e.g. in tests), falls back to the platform floor.
     */
    fun effectiveIntervalSeconds(context: Context): Long {
        val userPref = intervalSeconds(context)
        val communityFloor =
            try {
                org.openwebdav.messenger.ui.settings.UserSettings.communityMinPollSeconds.toLong()
            } catch (_: Exception) {
                PLATFORM_FLOOR_SECONDS
            }
        return maxOf(userPref, communityFloor, PLATFORM_FLOOR_SECONDS)
    }

    /**
     * Enable fast polling: persist the toggle + interval, cancel WorkManager, start the
     * foreground service. The user MUST have opted in — this is never auto-enabled.
     */
    fun enable(
        context: Context,
        workManager: WorkManager,
        intervalSeconds: Long = DEFAULT_INTERVAL,
    ) {
        prefs(context)
            .edit()
            .putBoolean(KEY_ENABLED, true)
            .putLong(KEY_INTERVAL_SECONDS, intervalSeconds)
            .apply()
        SyncScheduler.cancel(workManager)
        // Compute effective interval from the just-passed value (not from prefs — apply() is async).
        val communityFloor =
            try {
                org.openwebdav.messenger.ui.settings.UserSettings.communityMinPollSeconds.toLong()
            } catch (_: Exception) {
                PLATFORM_FLOOR_SECONDS
            }
        val effective = maxOf(intervalSeconds, communityFloor, PLATFORM_FLOOR_SECONDS)
        FastPollService.start(context, effective)
    }

    /**
     * Disable fast polling: persist the toggle off, stop the foreground service, re-schedule
     * the WorkManager periodic poll at its effective interval.
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
        // Schedule background poll at the effective interval.
        val memberPref = org.openwebdav.messenger.ui.settings.UserSettings.pollIntervalSeconds.toLong()
        val communityFloor = org.openwebdav.messenger.ui.settings.UserSettings.communityMinPollSeconds
        val effective = SyncScheduler.effectiveIntervalSeconds(memberPref, communityFloor)
        SyncScheduler.schedule(workManager, effective)
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
            SyncScheduler.cancel(workManager)
            FastPollService.start(context, effectiveIntervalSeconds(context))
        }
    }

    /** The default fast-poll interval: 300 seconds (5 minutes). The single source — [FastPollService] reads from here. */
    const val DEFAULT_INTERVAL = 300L

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
