package org.openwebdav.messenger

import android.app.Application
import android.util.Log
import androidx.work.WorkManager
import org.openwebdav.messenger.sync.FastPollManager

/**
 * Application entry point.
 *
 * The shipped substrates (transport, crypto, identity, message-model, sync) are backend-only — there
 * is no UI and no connection-config / roster management yet (those are later features). So the
 * Application class stays an intentionally empty host: the sync engine and its WorkManager poll are
 * exercised by tests, and are **activated** by the future config/UI feature that supplies the
 * connection config + roster and calls `SyncRunner.install(...)` + `SyncScheduler.schedule(...)`.
 * Until then `SyncWorker` runs the default no-op runner (a benign clean cycle). Later features wire
 * their dependency graph here.
 *
 * On start, the fast-poll preference is restored: if the user had enabled fast polling before the
 * process was killed, the foreground service is restarted and the WorkManager poll remains cancelled.
 *
 * ## Fast-poll toggle (for future settings UI)
 *
 * The fast-poll feature exposes a public API through [FastPollManager]. A future settings screen
 * (Compose or PreferenceFragment) calls:
 * ```
 *   (application as OpenWebDavMessengerApp).toggleFastPoll(true, intervalMinutes = 5)
 *   (application as OpenWebDavMessengerApp).toggleFastPoll(false)
 * ```
 * State persists in SharedPreferences; the toggle is OFF by default and the user must explicitly
 * opt in via the settings screen.
 */
class OpenWebDavMessengerApp : Application() {
    private val workManager: WorkManager by lazy {
        WorkManager.getInstance(this)
    }

    override fun onCreate() {
        super.onCreate()
        try {
            FastPollManager.restoreIfEnabled(this, workManager)
        } catch (e: IllegalStateException) {
            // WorkManager is not initialized — this happens in test environments (Robolectric)
            // where WorkManagerTestInitHelper initializes it after Application.onCreate().
            // Fast-poll restoration is a no-op in this case; the tests exercise the poll
            // cycle through SyncRunner directly and don't depend on the foreground service.
            Log.w(TAG, "WorkManager not initialized; fast-poll restoration skipped", e)
        }
    }

    /**
     * Enable or disable fast polling. The future settings UI calls this when the user toggles
     * the "Fast polling" switch.
     *
     * @param enable true to start the foreground service, false to stop it and fall back to
     *   WorkManager.
     * @param intervalMinutes the poll interval in minutes (default 5, the single source in
     *   [FastPollManager.DEFAULT_INTERVAL]).
     */
    fun toggleFastPoll(
        enable: Boolean,
        intervalMinutes: Long = FastPollManager.DEFAULT_INTERVAL,
    ) {
        if (enable) {
            FastPollManager.enable(this, workManager, intervalMinutes)
        } else {
            FastPollManager.disable(this, workManager)
        }
    }

    /** Whether fast polling is currently enabled (for the settings UI toggle state). */
    fun isFastPollEnabled(): Boolean = FastPollManager.isEnabled(this)

    companion object {
        private const val TAG = "OWDMApp"
    }
}
