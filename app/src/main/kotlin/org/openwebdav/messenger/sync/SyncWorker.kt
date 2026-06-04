package org.openwebdav.messenger.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

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
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val outcome = SyncRunner.current().runOnce()
        return if (outcome.backedOff) Result.retry() else Result.success()
    }
}
