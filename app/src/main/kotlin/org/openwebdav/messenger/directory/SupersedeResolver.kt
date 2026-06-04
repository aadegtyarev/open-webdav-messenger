package org.openwebdav.messenger.directory

/**
 * Resolves the §10.5 supersede rule (`docs/protocol/webdav-layout.md`): per **caller-supplied grouping
 * key**, keep the valid entry with the **maximum** signed `version-counter`; ties (equal counter)
 * broken by the **lexicographically-greater** entry file content-hash (§10.4 name) — an
 * arbitrary-but-total tiebreak all readers agree on. The counter is signed and monotonic, so an
 * attacker cannot make a stale entry win without the author's signing secret, and no wall clock is
 * trusted for "which wins".
 *
 * **Grouping key is supplied by the caller** (the edge generalization that lets both community
 * directories share this reducer): §10 supplies the verified Ed25519 signing-pubkey (so an entry signed
 * by a DIFFERENT key can never win for a member — the §10.5 security invariant); §11 supplies the
 * chat-id (so the latest signed descriptor wins per chat — under flat trust any member may publish a
 * competing descriptor, §11.5 / threat (a)). Everything downstream (counter compare, content-hash
 * tiebreak) is identical. A same-key counter reset degrades only display selection (the accepted
 * limitation, §10.5). Generic over the resolved value [T]; pure in-memory reduction, no I/O.
 */
internal class SupersedeResolver<T> {
    private val winners = HashMap<String, Candidate<T>>()

    /**
     * Offer a verified [value] under its caller-supplied [groupingKey] (the §10 signing-pubkey hex or
     * the §11 chat-id), with its signed [versionCounter] and §10.4/§11.4 [entryName], to the resolution.
     */
    fun offer(
        groupingKey: String,
        value: T,
        versionCounter: Long,
        entryName: String,
    ) {
        val candidate = Candidate(value, versionCounter, entryName)
        val current = winners[groupingKey]
        if (current == null || candidate.supersedes(current)) {
            winners[groupingKey] = candidate
        }
    }

    /** The resolved verified values (latest per grouping key). Order is not significant. */
    fun resolved(): List<T> = winners.values.map { it.value }

    private class Candidate<T>(
        val value: T,
        val versionCounter: Long,
        val entryName: String,
    ) {
        /**
         * §10.5: this candidate supersedes [other] iff its `version-counter` is strictly greater, OR
         * equal with a lexicographically-greater content-addressed entry name (the total tiebreak).
         * Unsigned-compare the uint64 counter so a counter ≥ 2^63 still orders correctly.
         */
        fun supersedes(other: Candidate<T>): Boolean {
            val cmp = java.lang.Long.compareUnsigned(versionCounter, other.versionCounter)
            if (cmp != 0) return cmp > 0
            return entryName > other.entryName
        }
    }
}
