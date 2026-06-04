package org.openwebdav.messenger.sync

/**
 * The seam the [SyncWorker] calls to run one poll cycle. It hides the runtime wiring (connection
 * config, member identity, joined subscriptions, key store) from the Worker so the Worker stays a
 * thin adapter and the run logic is testable without WorkManager.
 *
 * A real implementation builds the [SyncEngine] from the stored connection config + roster and the
 * `ChatKeyStore`, then calls [SyncEngine.pollCycle]. It is set once at app start via [install]; until
 * a config exists (no chat joined yet) the default no-op runner reports a clean, no-work cycle so a
 * scheduled poll before setup is a benign success.
 */
fun interface SyncRunner {
    /** Run one poll cycle; return its [CycleOutcome] (never throws — failures are folded into the result). */
    suspend fun runOnce(): CycleOutcome

    companion object {
        @Volatile
        private var installed: SyncRunner = SyncRunner { CycleOutcome(0, 0, backedOff = false) }

        /** Install the production runner (app start). Replaces any previously installed runner. */
        fun install(runner: SyncRunner) {
            installed = runner
        }

        /** The currently installed runner (the [SyncWorker] uses this). */
        fun current(): SyncRunner = installed
    }
}
