package org.openwebdav.messenger.sync

/**
 * The typed outcome of one poll cycle (`docs/features/sync_plan.md` → Poll cycle: "returns a typed
 * result … never throws"). A cycle NEVER throws into the caller — every failure is folded into one of
 * these shapes so the WorkManager wrapper can map it to `Result.success`/`Result.retry`.
 *
 * @property newCount messages newly persisted to Room this cycle (excludes dedup'd duplicates).
 * @property skippedCount files skipped this cycle — a hash mismatch, AEAD-open failure, signature
 *   failure, not-ready/incomplete body, or an unparseable change entry (§3 / §8.1 reject-don't-guess).
 *   A skip is benign: the entry is retried next cycle (incomplete) or permanently rejected (tampered).
 * @property backedOff `true` if a `429`/timeout/transport error interrupted the cycle mid-fetch. When
 *   set, the cursor was NOT advanced past the unfetched entries (§9.3), so the next run resumes there;
 *   the WorkManager wrapper maps this to a retry.
 * @property communityMinPollMinutes the community-governed polling floor read from `meta/community.json`
 *   during this cycle, or `null` if the file was not read (no-op cycle, pre-setup, etc.). The caller
 *   uses this to clamp the scheduling interval.
 */
data class CycleOutcome(
    val newCount: Int,
    val skippedCount: Int,
    val backedOff: Boolean,
    val communityMinPollMinutes: Int? = null,
    val retentionWindowDays: Int? = null,
)
