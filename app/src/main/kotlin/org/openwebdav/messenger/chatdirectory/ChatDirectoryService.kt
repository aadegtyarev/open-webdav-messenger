package org.openwebdav.messenger.chatdirectory

import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.directory.SupersedeResolver
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.protocol.Envelope
import org.openwebdav.messenger.protocol.Hex
import org.openwebdav.messenger.transport.InboxEntry
import org.openwebdav.messenger.transport.ReadResult
import org.openwebdav.messenger.transport.WebDavResult
import org.openwebdav.messenger.transport.WebDavTransport

/**
 * The §11 community-chat-directory orchestration seam (`docs/features/chat-directory_plan.md` →
 * Contracts; `docs/protocol/webdav-layout.md` §11). Pure orchestration over [WebDavTransport] (verbs),
 * [ChatDirectoryCrypto] (seal/open + sign/verify), and [ChatDirectoryPaths] (collection +
 * content-addressed name) — no HTTP, no SQL, no crypto in this class (it composes the lower layers,
 * staying inside the file/function-size limits). The direct sibling of the §10 `DirectoryService`, with
 * a chat descriptor instead of a member identity, grouped by chat-id (§11.5), and a `dm`-drop privacy
 * gate at both publish and read. Two operations, both typed and never-throwing:
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
    private val transport: WebDavTransport,
    private val crypto: ChatDirectoryCrypto,
) {
    /**
     * §11.4 publish: build the group-chat descriptor from [chatId] + [access] + [title] at
     * [versionCounter], Ed25519-sign it with [identity]'s signing key, AEAD-seal under [communityKey],
     * and PUT it to the content-addressed path (after an idempotent `MKCOL` of `chat-directory/`). A
     * [kind] of [ChatKind.DM] is **rejected before any write** ([ChatPublishOutcome.RejectedDm]) — DMs
     * are never discoverable (§11.5). The signing **secret** is read from [identity] in-memory and never
     * leaves; only the public key is published (SC5). Returns a typed [ChatPublishOutcome] — never throws.
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
        val envelopeBytes =
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
        if (transport.ensureCollection(ChatDirectoryPaths.CHAT_DIRECTORY) !is WebDavResult.Success) {
            return ChatPublishOutcome.Failed("could not ensure chat-directory/ collection (transport back-off)")
        }
        val entryName = ChatDirectoryPaths.entryName(envelopeBytes)
        return when (transport.write(ChatDirectoryPaths.entryPath(entryName), envelopeBytes)) {
            is WebDavResult.Success -> ChatPublishOutcome.Published(entryName)
            else -> ChatPublishOutcome.Failed("could not write chat-directory entry (transport back-off)")
        }
    }

    /**
     * §11.6 read: one `PROPFIND Depth: 1` on `chat-directory/`, then `GET`/open/verify each entry, drop
     * `dm`-kind + invalid entries, and resolve the latest valid descriptor per chat-id (§11.5). A
     * listing failure yields an empty result with `listingFailed = true` (retry next cycle); a per-entry
     * failure increments `rejectedCount` and is dropped, never wedging the read (flat-trust degradation).
     * Never throws.
     */
    suspend fun readChatDirectory(communityKey: ChatKey): ChatDirectoryReadResult {
        val entries =
            when (val listed = transport.list(ChatDirectoryPaths.CHAT_DIRECTORY)) {
                is WebDavResult.Success -> listed.value
                else -> return ChatDirectoryReadResult(entries = emptyList(), rejectedCount = 0, listingFailed = true)
            }
        val resolver = SupersedeResolver<ChatDirectoryEntry>()
        var rejected = 0
        for (entry in entries) {
            when (val outcome = fetchAndVerify(entry, communityKey)) {
                is FetchOutcome.Verified ->
                    // §11.5: group by the opaque chat-id (hex-keyed for a stable map key over the raw
                    // bytes). Under flat trust any member may publish a competing descriptor for a
                    // chat-id; the latest signed version wins (the signature is tamper-evidence on the
                    // bytes + authorship of THIS version, NOT chat-ownership authority — threat (a)).
                    resolver.offer(
                        groupingKey = outcome.chatIdHex,
                        value = outcome.entry,
                        versionCounter = outcome.versionCounter,
                        entryName = outcome.entryName,
                    )
                FetchOutcome.Dropped -> rejected++
                FetchOutcome.NotReady -> rejected++ // transient (incomplete upload / hash mismatch) — retried next read
            }
        }
        return ChatDirectoryReadResult(entries = resolver.resolved(), rejectedCount = rejected)
    }

    /**
     * §11.6 per-entry path: validate the name (SC16 / reject-don't-guess), `GET` with the §3/§11.6
     * content-hash check, re-frame, AEAD-open with the community key, §11.3-parse + verify + `dm`-drop.
     * A non-`none` codec is rejected explicitly (this feature inflates only `codec-id = 0x00`).
     */
    private suspend fun fetchAndVerify(
        entry: InboxEntry,
        communityKey: ChatKey,
    ): FetchOutcome {
        if (entry.isCollection) return FetchOutcome.Dropped
        // SC16 / reject-don't-guess: a name outside the §11.4 grammar is dropped before any GET (it is
        // never dereferenced — path-traversal-safe by construction).
        if (!ChatDirectoryPaths.isWellFormedEntryName(entry.name)) return FetchOutcome.Dropped
        val path = ChatDirectoryPaths.entryPath(entry.name)
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
        return when (val parsed = crypto.openDescriptor(envelopeBytes, communityKey)) {
            is ChatParseResult.Parsed -> {
                val d = parsed.descriptor
                FetchOutcome.Verified(
                    entry = ChatDirectoryEntry(d.chatId, d.kind, d.access, d.title, d.signingPublic),
                    chatIdHex = Hex.encode(d.chatId),
                    versionCounter = d.versionCounter,
                    entryName = entry.name,
                )
            }
            // wrong key / bad sig / malformed / DM-kind / invalid access (§11.6 — the dm-drop is here).
            is ChatParseResult.Rejected -> FetchOutcome.Dropped
        }
    }

    /** One entry's read outcome inside [readChatDirectory]. */
    private sealed interface FetchOutcome {
        data class Verified(
            val entry: ChatDirectoryEntry,
            val chatIdHex: String,
            val versionCounter: Long,
            val entryName: String,
        ) : FetchOutcome

        /** Permanently rejected: wrong/absent key, bad signature, malformed, dm-kind, foreign name, bad codec (§11.6). */
        data object Dropped : FetchOutcome

        /** Transient: incomplete upload / content-hash mismatch / transport back-off — retried next read. */
        data object NotReady : FetchOutcome
    }
}
