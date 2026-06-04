package org.openwebdav.messenger.directory

import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.protocol.Envelope
import org.openwebdav.messenger.protocol.Hex
import org.openwebdav.messenger.transport.InboxEntry
import org.openwebdav.messenger.transport.ReadResult
import org.openwebdav.messenger.transport.WebDavResult
import org.openwebdav.messenger.transport.WebDavTransport

/**
 * The §10 community-user-directory orchestration seam (`docs/features/directory_plan.md` → Contracts;
 * `docs/protocol/webdav-layout.md` §10). Pure orchestration over [WebDavTransport] (verbs),
 * [DirectoryCrypto] (seal/open + sign/verify), and [DirectoryPaths] (collection + content-addressed
 * name) — no HTTP, no SQL, no crypto in this class (it composes the lower layers, staying inside the
 * file/function-size limits). Two operations, both typed and never-throwing:
 *
 *  - [publishEntry] — seal the member's signed entry under the community key and write it once to the
 *    `directory/` collection as a content-addressed append-only file (§10.4). Idempotent on identical
 *    re-publish; a changed entry is a new superseding version (§10.5).
 *  - [readDirectory] — list `directory/` (one `PROPFIND Depth: 1`), `GET`/open/verify each entry, and
 *    resolve the latest valid entry per signing-pubkey (§10.5/§10.6). Every per-entry failure is a
 *    typed rejection — dropped, never surfaced — and the remaining valid entries still read.
 *
 * The [WebDavTransport] passed in is scoped to the **community-root** (its config's root path is the
 * community-root, §10.1) — distinct from a chat-root-scoped transport. No local cache: verified entries
 * are recomputed from the on-disk source of truth per read (§10.6; the Room cache is the UI feature's).
 */
class DirectoryService internal constructor(
    private val transport: WebDavTransport,
    private val crypto: DirectoryCrypto,
) {
    /**
     * §10.4 publish: build the entry from [identity]'s two public keys + [displayName] at
     * [versionCounter], Ed25519-sign it, AEAD-seal under [communityKey], and PUT it to the
     * content-addressed path (after an idempotent `MKCOL` of `directory/`). The signing **secret** is
     * read from [identity] in-memory and never leaves; only public keys are published (SC5). Returns a
     * typed [PublishOutcome] — never throws.
     *
     * @param versionCounter the signed per-author monotonic supersede coordinate (§10.5); the caller
     *   increments it per re-publish. A re-publish of identical (displayName, keys, versionCounter)
     *   produces a different file (random AEAD nonce) UNLESS the caller reuses the same bytes; supersede
     *   is resolved at read time regardless (§10.5), so a duplicate is harmless.
     */
    suspend fun publishEntry(
        identity: Identity,
        displayName: String,
        versionCounter: Long,
        communityKey: ChatKey,
    ): PublishOutcome {
        val signingSecret = identity.copySignSecret()
        val envelopeBytes =
            try {
                crypto.sealEntry(
                    displayName = displayName,
                    signingPublic = identity.copySignPublic(),
                    boxPublic = identity.copyBoxPublic(),
                    versionCounter = versionCounter,
                    signingSecret = signingSecret,
                    communityKey = communityKey,
                )
            } finally {
                // The signing secret was copied out of the Keystore-backed identity for the in-memory
                // sign; wipe our copy as soon as the seal is done (Security constraints / SC5 family).
                signingSecret.fill(0)
            }
        if (transport.ensureCollection(DirectoryPaths.DIRECTORY) !is WebDavResult.Success) {
            return PublishOutcome.Failed("could not ensure directory/ collection (transport back-off)")
        }
        val entryName = DirectoryPaths.entryName(envelopeBytes)
        return when (transport.write(DirectoryPaths.entryPath(entryName), envelopeBytes)) {
            is WebDavResult.Success -> PublishOutcome.Published(entryName)
            else -> PublishOutcome.Failed("could not write directory entry (transport back-off)")
        }
    }

    /**
     * §10.6 read: one `PROPFIND Depth: 1` on `directory/`, then `GET`/open/verify each entry and resolve
     * the latest valid entry per signing-pubkey (§10.5). A listing failure yields an empty result with
     * `listingFailed = true` (retry next cycle); a per-entry failure increments `rejectedCount` and is
     * dropped, never wedging the read (flat-trust degradation). Never throws.
     */
    suspend fun readDirectory(communityKey: ChatKey): DirectoryReadResult {
        val entries =
            when (val listed = transport.list(DirectoryPaths.DIRECTORY)) {
                is WebDavResult.Success -> listed.value
                else -> return DirectoryReadResult(entries = emptyList(), rejectedCount = 0, listingFailed = true)
            }
        val resolver = SupersedeResolver<DirectoryEntry>()
        var rejected = 0
        for (entry in entries) {
            when (val outcome = fetchAndVerify(entry, communityKey)) {
                is FetchOutcome.Verified ->
                    // §10.5: group by the verified signing-pubkey (hex-keyed for a stable map key).
                    resolver.offer(
                        groupingKey = Hex.encode(outcome.entry.copySigningPublicKey()),
                        value = outcome.entry,
                        versionCounter = outcome.versionCounter,
                        entryName = outcome.entryName,
                    )
                FetchOutcome.Dropped -> rejected++
                FetchOutcome.NotReady -> rejected++ // transient (incomplete upload / hash mismatch) — counted, retried next read
            }
        }
        return DirectoryReadResult(entries = resolver.resolved(), rejectedCount = rejected)
    }

    /**
     * §10.6 per-entry path: validate the name (SC16 / reject-don't-guess), `GET` with the §3/§10.6
     * content-hash check, re-frame, AEAD-open with the community key, §10.3-parse + verify. A non-`none`
     * codec is rejected explicitly (this feature inflates only `codec-id = 0x00`, like the sync reader).
     */
    private suspend fun fetchAndVerify(
        entry: InboxEntry,
        communityKey: ChatKey,
    ): FetchOutcome {
        if (entry.isCollection) return FetchOutcome.Dropped
        // SC16 / reject-don't-guess: a name outside the §10.4 grammar is dropped before any GET (it is
        // never dereferenced — path-traversal-safe by construction).
        if (!DirectoryPaths.isWellFormedEntryName(entry.name)) return FetchOutcome.Dropped
        val path = DirectoryPaths.entryPath(entry.name)
        val blobResult =
            when (val read = transport.readContentAddressed(path, entry.name)) {
                is WebDavResult.Success ->
                    when (val r = read.value) {
                        is ReadResult.Ready -> r
                        ReadResult.NotReady -> return FetchOutcome.NotReady
                    }
                else -> return FetchOutcome.NotReady // 429 / timeout / transport error → retry next read
            }
        if (blobResult.codecId != Envelope.CODEC_NONE) return FetchOutcome.Dropped
        val envelopeBytes = Envelope.frame(blobResult.codecId, blobResult.blob)
        return when (val parsed = crypto.openEntry(envelopeBytes, communityKey)) {
            is DirectoryParseResult.Parsed -> {
                val p = parsed.entry
                FetchOutcome.Verified(
                    entry = DirectoryEntry(p.displayName, p.signingPublic, p.boxPublic),
                    versionCounter = p.versionCounter,
                    entryName = entry.name,
                )
            }
            is DirectoryParseResult.Rejected -> FetchOutcome.Dropped // wrong key / bad sig / malformed (§10.6)
        }
    }

    /** One entry's read outcome inside [readDirectory]. */
    private sealed interface FetchOutcome {
        data class Verified(val entry: DirectoryEntry, val versionCounter: Long, val entryName: String) : FetchOutcome

        /** Permanently rejected: wrong/absent key, bad signature, malformed, foreign name, bad codec (§10.6). */
        data object Dropped : FetchOutcome

        /** Transient: incomplete upload / content-hash mismatch / transport back-off — retried next read. */
        data object NotReady : FetchOutcome
    }
}
