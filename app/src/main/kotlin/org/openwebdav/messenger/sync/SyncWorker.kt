package org.openwebdav.messenger.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.openwebdav.messenger.app.AppContainer

/**
 * The WorkManager wrapper around one poll cycle (arch note Variant A: "a thin wrapper that calls
 * `pollCycle()` and maps the result to `Result.success/retry`"). One run == one poll cycle
 * (`docs/features/sync_plan.md` → Decisions / Background loop).
 *
 * It is a [CoroutineWorker] so the cycle's `suspend` I/O runs off the main thread. It holds NO sync
 * logic itself — it delegates to the installed [SyncRunner] and maps the typed [CycleOutcome]:
 *  - `backedOff` (a `429`/timeout interrupted the fetch) → [Result.retry] so WorkManager re-runs; the
 *    §9.3 cursor was NOT advanced past the unfetched entries, so the retry resumes with no loss.
 *  - otherwise → [Result.success] (new + skipped counts are diagnostic, not a failure signal).
 *
 * A run deferred for hours by Doze (stack-notes WorkManager) simply resumes from the stored cursor on
 * the next run — the cursor is durable local state, so no message within the window is lost.
 *
 * **Cold-start safety:** after process death WorkManager can start this worker in a fresh process before
 * the `Application` has installed the real runner. [AppContainer.ensureWarmStarted] runs (and awaits)
 * process-start wiring first, so a post-process-death poll reads the real runner — not the default no-op
 * — and does not silently skip a delivery window (review finding 2).
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        AppContainer.bind(applicationContext)
        AppContainer.ensureWarmStarted()
        val outcome = SyncRunner.current().runOnce()
        return if (outcome.backedOff) Result.retry() else Result.success()
    }
}
