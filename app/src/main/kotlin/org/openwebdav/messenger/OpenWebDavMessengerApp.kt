package org.openwebdav.messenger

import android.app.Application
import android.util.Log
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.openwebdav.messenger.app.AppContainer
import org.openwebdav.messenger.sync.FastPollManager

/**
 * Application entry point.
 *
 * As of the `ui-chat-surface` feature this is a thin delegator to the composition root [AppContainer]
 * (arch note Choice 1, Option A): it binds the application context and kicks off the process-start engine
 * wiring on a background dispatcher (the wiring reads the Keystore-wrapped connection config + chat key,
 * which must not touch the main thread). When a config exists, [AppContainer.warmStart] installs the real
 * `SyncRunner` + schedules the poll; when none exists yet (fresh install, before connect/join) the default
 * no-op runner stays installed so a scheduled poll before setup is a benign clean cycle.
 */
class OpenWebDavMessengerApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "OWDMApp"
    }

    override fun onCreate() {
        super.onCreate()
        AppContainer.bind(this)
        // Off the main thread: the wiring unwraps Keystore-backed secrets and may touch Room/IO.
        appScope.launch {
            try {
                AppContainer.warmStart()
            } catch (e: Exception) {
                Log.e(TAG, "warmStart failed", e)
            }
        }
        try {
            FastPollManager.restoreIfEnabled(this, WorkManager.getInstance(this))
        } catch (_: IllegalStateException) {
            // WorkManager not initialized (e.g. in test environments) — fast polling
            // will be restored when the first SyncWorker is scheduled.
        }
    }
}
