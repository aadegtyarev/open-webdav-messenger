package org.openwebdav.messenger.chatdirectory

import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.directory.CollectionPaths
import org.openwebdav.messenger.directory.CommunityDirectoryEngine
import org.openwebdav.messenger.directory.VerifyResult
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.protocol.Hex
import org.openwebdav.messenger.transport.WebDavTransport

/**
 * The §11 community-chat-directory orchestration seam (`docs/features/chat-directory_plan.md` →
 * Contracts; `docs/protocol/webdav-layout.md` §11). A thin §11-named face over the shared
 * [CommunityDirectoryEngine] (the publish + read pipelines it shares with the §10 user directory) plus
 * [ChatDirectoryCrypto] (seal/open + sign/verify). The direct sibling of the §10 `DirectoryService`,
 * with a chat descriptor instead of a member identity, grouped by chat-id (§11.5), and a `dm`-drop
 * privacy gate at both publish and read. Two operations, both typed and never-throwing:
 *
 *  - [publishChatEntry] — seal the member's signed group-chat descriptor under the community key and
 *    write it once to the `chat-directory/` collection as a content-addressed append-only file (§11.4).
 *    A `dm` kind is **rejected at publish — nothing is written** (§11.5). Idempotent on identical
 *    re-publish; a changed descriptor is a new superseding version (§11.5).
 *  - [readChatDirectory] — list `chat-directory/` (one `PROPFIND Depth: 1`), `GET`/open/verify each
 *    entry, **drop `dm`-kind + invalid-kind/access entries**, and resolve the latest valid descriptor
 *    per chat-id (§11.5/§11.6). Every per-entry failure is a typed rejection — dropped, never surfaced —
 *    and the remaining valid entries still read.
 *
 * The [WebDavTransport] passed in is scoped to the **community-root** (§11.1) — distinct from a
 * chat-root-scoped transport. No local cache: verified descriptors are recomputed from the on-disk
 * source of truth per read (§11.6; the Room cache is the UI feature's).
 */
class ChatDirectoryService internal constructor(
    transport: WebDavTransport,
    private val crypto: ChatDirectoryCrypto,
) {
    private val engine =
        CommunityDirectoryEngine<ChatDirectoryEntry>(
            transport = transport,
            paths = CollectionPaths(ChatDirectoryPaths.CHAT_DIRECTORY),
            verify = ::verifyDescriptor,
        )

    /**
     * §11.4 publish: build the group-chat descriptor from [chatId] + [access] + [title] at
     * [versionCounter], Ed25519-sign it with [identity]'s signing key, AEAD-seal under [communityKey],
     * and PUT it to the content-addressed path (after an idempotent `MKCOL` of `chat-directory/`). A
     * [kind] of [ChatKind.DM] is **rejected before any write** ([ChatPublishOutcome.RejectedDm]) — DMs
     * are never discoverable (§11.5). The signing **secret** is read from [identity] in-memory and never
     * leaves; only the public key is published (SC5). Returns a typed [ChatPublishOutcome] — never throws
     * (an invalid parameter or a native seal failure both degrade to [ChatPublishOutcome.Failed], C8).
     *
     * @param versionCounter the signed per-chat-id monotonic supersede coordinate (§11.5); the caller
     *   increments it per re-publish. Supersede is resolved at read time (§11.5).
     */
    suspend fun publishChatEntry(
        identity: Identity,
        chatId: ByteArray,
        kind: ChatKind,
        access: ChatAccess,
        title: String,
        versionCounter: Long,
        communityKey: ChatKey,
    ): ChatPublishOutcome {
        // §11.5 privacy gate at publish: a DM is never written to the chat directory. Refuse before
        // touching the disk (a permanent, intentional refusal — not a transport retry).
        if (kind == ChatKind.DM) return ChatPublishOutcome.RejectedDm

        val signingSecret = identity.copySignSecret()
        return engine.publish(
            seal = {
                try {
                    crypto.sealDescriptor(
                        chatId = chatId,
                        kind = kind,
                        access = access,
                        title = title,
                        signingPublic = identity.copySignPublic(),
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
            published = { ChatPublishOutcome.Published(it) },
            failed = { ChatPublishOutcome.Failed(it) },
        )
    }

    /**
     * §11.6 read: one `PROPFIND Depth: 1` on `chat-directory/`, then `GET`/open/verify each entry, drop
     * `dm`-kind + invalid entries, and resolve the latest valid descriptor per chat-id (§11.5). A
     * listing failure yields an empty result with `listingFailed = true` (retry next cycle); a per-entry
     * failure increments `rejectedCount` and is dropped, never wedging the read (flat-trust degradation).
     * Never throws.
     */
    suspend fun readChatDirectory(communityKey: ChatKey): ChatDirectoryReadResult {
        val outcome = engine.read(communityKey)
        return ChatDirectoryReadResult(
            entries = outcome.entries,
            rejectedCount = outcome.rejectedCount,
            listingFailed = outcome.listingFailed,
        )
    }

    /**
     * Open + §11.3-verify one envelope into a verified [ChatDirectoryEntry] grouped by the opaque chat-id
     * hex (§11.5: under flat trust any member may publish a competing descriptor for a chat-id; the
     * latest signed version wins — the signature is tamper-evidence on the bytes + authorship of THIS
     * version, NOT chat-ownership authority, threat (a)), or a typed drop (wrong key / bad sig /
     * malformed / `dm`-kind / invalid access — the §11.6 dm-drop is inside the codec parse).
     */
    private fun verifyDescriptor(
        communityKey: ChatKey,
        envelopeBytes: ByteArray,
    ): VerifyResult<ChatDirectoryEntry> =
        when (val parsed = crypto.openDescriptor(envelopeBytes, communityKey)) {
            is ChatParseResult.Parsed -> {
                val d = parsed.descriptor
                VerifyResult.Verified(
                    value = ChatDirectoryEntry(d.chatId, d.kind, d.access, d.title, d.signingPublic),
                    groupingKey = Hex.encode(d.chatId),
                    versionCounter = d.versionCounter,
                )
            }
            is ChatParseResult.Rejected -> VerifyResult.Dropped
        }
}
