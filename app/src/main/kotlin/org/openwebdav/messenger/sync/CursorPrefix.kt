package org.openwebdav.messenger.sync

/**
 * One log entry's fetch outcome (used by [PollReader]). [NotReady] and [Rejected] both "skip" the entry
 * for the cycle, but differ in cursor effect (`docs/protocol/webdav-layout.md` §9.3 / code-review
 * finding 1): a transient [NotReady] (incomplete upload / §3 hash-mismatch) blocks the cursor at a
 * forward gap (retry); a permanent [Rejected] (AEAD/signature fail / unsupported codec — a forgery a
 * member can plant under flat trust) must NOT wedge the cursor and is advanced past.
 */
internal sealed interface FetchStep {
    data class Persisted(val orderToken: String, val inserted: Boolean) : FetchStep

    /** Transient: the file is not fully readable yet — leave the cursor, retry next cycle (§3). */
    data object NotReady : FetchStep

    /** Permanent: the file is forged/tampered/unsupported-codec — skip, but do not wedge the cursor. */
    data object Rejected : FetchStep

    data object BackOff : FetchStep
}

/**
 * Computes the cursor coordinate of the longest contiguous resolved prefix while draining a chat
 * (`docs/protocol/webdav-layout.md` §9.3 / code-review finding 1), grouping the entries by their
 * order-token **coordinate** (the cursor's unit). Entries arrive in ascending §2-name order, so a
 * coordinate's entries are contiguous.
 *
 * A coordinate is **resolved** iff at least one of its entries resolved (persisted, dedup, or
 * permanently Rejected — a forged/tampered file under flat trust must not wedge the cursor). A
 * coordinate all of whose entries are transient NotReady (incomplete upload / §3 hash-mismatch) is a
 * **gap**: the cursor freezes at the last resolved coordinate before it, so the gap is retried next
 * cycle and never skipped past by a later complete entry. A NotReady that shares a coordinate with a
 * resolved sibling opens no gap (the coordinate is reachable) — this is the forged-file-at-an-already-
 * witnessed-coordinate case the §3 flat-trust degradation tolerates.
 */
internal class CursorPrefix(start: String) {
    private var advanced: String = start
    private var frozen = false

    // The coordinate group currently being accumulated (entries are ascending, so a group is
    // contiguous); committed when the next, strictly-greater coordinate begins or at finish().
    private var groupToken: String? = null
    private var groupResolved = false

    /** Record one entry at [orderToken]; [resolved] = it persisted/deduped or was permanently rejected. */
    fun record(
        orderToken: String,
        resolved: Boolean,
    ) {
        if (frozen) return
        val current = groupToken
        if (current != null && orderToken != current) commit(current)
        if (frozen) return
        groupToken = orderToken
        if (resolved) groupResolved = true
    }

    /** Commit the final group and return the coordinate the cursor may advance to (never below `start`). */
    fun finish(): String {
        groupToken?.let { if (!frozen) commit(it) }
        return advanced
    }

    private fun commit(token: String) {
        if (groupResolved) {
            if (token > advanced) advanced = token // coordinate reachable → cursor may pass it
        } else {
            frozen = true // a coordinate with no resolved entry is a forward gap → freeze here
        }
        groupResolved = false
    }
}
