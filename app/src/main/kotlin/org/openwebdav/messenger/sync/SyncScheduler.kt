package org.openwebdav.messenger.sync

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules the background poll as a [PeriodicWorkRequest] (`docs/features/sync_plan.md` → Decisions:
 * background polling via WorkManager). The repeat interval is clamped to the platform floor
 * `PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS` (900000 ms = 15 min): requesting a shorter
 * interval does not give a shorter one — WorkManager clamps it (stack-notes WorkManager:
 * <https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work#schedule_periodic_work>).
 *
 * "15 min is a floor, not a guarantee" — Doze/App-Standby defer further; the §9.3 cursor absorbs the
 * skipped/late runs (resume-from-cursor), so the schedule only needs to request the floor, not enforce
 * a cadence the OS will never honour exactly.
 *
 * The effective interval is `max(memberPreference, communityFloor, platformFloor)`. A member can only
 * INCREASE their interval above the community floor, never go below it.
 */
object SyncScheduler {
    /** Unique work name so re-scheduling replaces rather than stacks the periodic request. */
    const val WORK_NAME = "owdm.sync.poll"

    /** The WorkManager platform floor: 15 minutes (900000 ms). */
    const val PLATFORM_FLOOR_MINUTES = 15L

    /**
     * Compute the effective WorkManager poll interval: `max(memberPref, communityFloor, platformFloor)`.
     * [communityFloor] may be null (metadata not read yet) — defaults to [PLATFORM_FLOOR_MINUTES].
     */
    fun effectiveIntervalMinutes(
        memberPref: Long,
        communityFloor: Int?,
    ): Long = maxOf(memberPref, (communityFloor ?: PLATFORM_FLOOR_MINUTES.toInt()).toLong(), PLATFORM_FLOOR_MINUTES)

    /**
     * Build a periodic poll request at `max(requestedMinutes, 15-min floor)`. Exposed (not just used
     * internally) so a test can assert the enqueued interval is ≥ the floor without enqueuing
     * (`periodic_request_clamped_to_15min_floor`).
     */
    fun pollRequest(requestedMinutes: Long): PeriodicWorkRequest {
        val requestedMillis = TimeUnit.MINUTES.toMillis(requestedMinutes)
        val intervalMillis = maxOf(requestedMillis, PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS)
        return PeriodicWorkRequest
            .Builder(SyncWorker::class.java, intervalMillis, TimeUnit.MILLISECONDS)
            .build()
    }

    /** Enqueue (or replace) the periodic poll under [WORK_NAME] at [requestedMinutes]. */
    fun schedule(
        workManager: WorkManager,
        requestedMinutes: Long = DEFAULT_INTERVAL_MINUTES,
    ) {
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            pollRequest(requestedMinutes),
        )
    }

    /** Cancel the periodic poll. Idempotent — safe to call even when nothing is scheduled. */
    fun cancel(workManager: WorkManager) {
        workManager.cancelUniqueWork(WORK_NAME)
    }

    /** Default requested interval = the platform floor. */
    const val DEFAULT_INTERVAL_MINUTES = PLATFORM_FLOOR_MINUTES
}
