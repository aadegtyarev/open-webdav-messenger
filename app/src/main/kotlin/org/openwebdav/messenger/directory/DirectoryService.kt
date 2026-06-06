package org.openwebdav.messenger.directory

import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.protocol.Hex
import org.openwebdav.messenger.transport.WebDavTransport

/**
 * The §10 community-user-directory orchestration seam (`docs/features/directory_plan.md` → Contracts;
 * `docs/protocol/webdav-layout.md` §10). A thin §10-named face over the shared
 * [CommunityDirectoryEngine] (the publish + read pipelines it shares with the §11 chat directory) plus
 * [DirectoryCrypto] (seal/open + sign/verify). No HTTP, no SQL, no crypto in this class. Two operations,
 * both typed and never-throwing:
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
    transport: WebDavTransport,
    private val crypto: DirectoryCrypto,
) {
    private val engine =
        CommunityDirectoryEngine<DirectoryEntry>(
            transport = transport,
            paths = CollectionPaths(DirectoryPaths.DIRECTORY),
            verify = ::verifyEntry,
        )

    /**
     * §10.4 publish: build the entry from [identity]'s two public keys + [displayName] at
     * [versionCounter], Ed25519-sign it, AEAD-seal under [communityKey], and PUT it to the
     * content-addressed path (after an idempotent `MKCOL` of `directory/`). The signing **secret** is
     * read from [identity] in-memory and never leaves; only public keys are published (SC5). Returns a
     * typed [PublishOutcome] — never throws (an invalid parameter or a native seal failure both degrade
     * to [PublishOutcome.Failed], C8).
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
        return engine.publish(
            seal = {
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
            },
            published = { PublishOutcome.Published(it) },
            failed = { PublishOutcome.Failed(it) },
        )
    }

    /**
     * §10.6 read: one `PROPFIND Depth: 1` on `directory/`, then `GET`/open/verify each entry and resolve
     * the latest valid entry per signing-pubkey (§10.5). A listing failure yields an empty result with
     * `listingFailed = true` (retry next cycle); a per-entry failure increments `rejectedCount` and is
     * dropped, never wedging the read (flat-trust degradation). Never throws.
     */
    suspend fun readDirectory(communityKey: ChatKey): DirectoryReadResult {
        val outcome = engine.read(communityKey)
        return DirectoryReadResult(
            entries = outcome.entries,
            rejectedCount = outcome.rejectedCount,
            listingFailed = outcome.listingFailed,
        )
    }

    /**
     * Open + §10.3-verify one envelope into a verified [DirectoryEntry] grouped by its verified
     * signing-pubkey hex (§10.5: an entry signed by a DIFFERENT key can never win for a member — the
     * security invariant), or a typed drop (wrong key / bad sig / malformed, §10.6).
     */
    private fun verifyEntry(
        communityKey: ChatKey,
        envelopeBytes: ByteArray,
    ): VerifyResult<DirectoryEntry> =
        when (val parsed = crypto.openEntry(envelopeBytes, communityKey)) {
            is DirectoryParseResult.Parsed -> {
                val p = parsed.entry
                VerifyResult.Verified(
                    value = DirectoryEntry(p.displayName, p.signingPublic, p.boxPublic),
                    groupingKey = Hex.encode(p.signingPublic),
                    versionCounter = p.versionCounter,
                )
            }
            is DirectoryParseResult.Rejected -> VerifyResult.Dropped
        }
}
