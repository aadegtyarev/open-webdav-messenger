package org.openwebdav.messenger.sync

/**
 * The typed outcome of a [SyncEngine.send] (`docs/features/sync_plan.md` → Send: "tolerant of partial
 * failure (retry-safe)"). Send never throws; a partial failure is reported, not raised, and re-running
 * the same send is idempotent on the §2 message-id (re-`PUT`s the same `log/` file + change entries as
 * no-ops, §9.1).
 *
 * @property logWritten `true` if the single shared-`log/` write succeeded (the message is on the disk).
 * @property notifiedMembers count of member change indices successfully updated this attempt.
 * @property pendingMembers count of member change indices that did NOT get a change entry this attempt
 *   (a `429`/timeout/transport error). The log write may already be durable; re-running the send
 *   completes the missing notifies idempotently — no torn state (§9.1, no multi-file transaction).
 */
data class SendOutcome(
    val logWritten: Boolean,
    val notifiedMembers: Int,
    val pendingMembers: Int,
) {
    /** `true` when the message landed in `log/` AND every member's change index was notified. */
    val complete: Boolean get() = logWritten && pendingMembers == 0
}
