package org.openwebdav.messenger.directory

/**
 * Resolves the §10.5 supersede rule (`docs/protocol/webdav-layout.md`): per Ed25519 signing-pubkey,
 * keep the valid entry with the **maximum** signed `version-counter`; ties (equal counter) broken by
 * the **lexicographically-greater** entry file content-hash (§10.4 name) — an arbitrary-but-total
 * tiebreak all readers agree on. The counter is signed and monotonic, so an attacker cannot make a
 * stale entry win without the author's signing secret, and no wall clock is trusted for "which wins".
 *
 * Grouping is by the verified signing-pubkey, so an entry signed by a DIFFERENT key can never win for
 * a member (the security invariant of §10.5); a same-key counter reset degrades only display selection
 * (the accepted limitation, §10.5). Pure in-memory reduction, no I/O.
 */
internal class SupersedeResolver {
    private val winners = HashMap<String, Candidate>()

    /** Offer a verified [entry] (its signed [versionCounter] and §10.4 [entryName]) to the resolution. */
    fun offer(
        entry: DirectoryEntry,
        versionCounter: Long,
        entryName: String,
    ) {
        // Group by the verified signing-pubkey (hex-keyed for a stable map key over the raw bytes).
        val key = entry.copySigningPublicKey().joinToString("") { "%02x".format(it) }
        val candidate = Candidate(entry, versionCounter, entryName)
        val current = winners[key]
        if (current == null || candidate.supersedes(current)) {
            winners[key] = candidate
        }
    }

    /** The resolved verified entries (latest per signing-pubkey). Order is not significant. */
    fun resolved(): List<DirectoryEntry> = winners.values.map { it.entry }

    private class Candidate(
        val entry: DirectoryEntry,
        val versionCounter: Long,
        val entryName: String,
    ) {
        /**
         * §10.5: this candidate supersedes [other] iff its `version-counter` is strictly greater, OR
         * equal with a lexicographically-greater content-addressed entry name (the total tiebreak).
         * Unsigned-compare the uint64 counter so a counter ≥ 2^63 still orders correctly.
         */
        fun supersedes(other: Candidate): Boolean {
            val cmp = java.lang.Long.compareUnsigned(versionCounter, other.versionCounter)
            if (cmp != 0) return cmp > 0
            return entryName > other.entryName
        }
    }
}
