package org.openwebdav.messenger.chatdirectory

/**
 * The result of `ChatDirectoryService.publishChatEntry` (§11.4 — `docs/protocol/webdav-layout.md`). The
 * publish is idempotent on identical content (same bytes → same content-addressed name → idempotent
 * `PUT`); a changed descriptor is a new superseding version (§11.5). Typed — never thrown.
 */
sealed interface ChatPublishOutcome {
    /**
     * The descriptor was written (or was already present — an idempotent re-publish of identical bytes).
     * [entryName] is the §11.4 content-addressed file name the entry landed at.
     */
    data class Published(val entryName: String) : ChatPublishOutcome

    /**
     * A `dm`-kind descriptor was REJECTED at publish — **nothing is written to disk** (DMs are never
     * discoverable, the social-graph privacy gate, §11.5 / PM scope decision 2026-06-04). This is a
     * distinct outcome from a transport failure: it is a permanent, intentional refusal, not a retry.
     */
    data object RejectedDm : ChatPublishOutcome

    /**
     * The write could not complete this attempt (transport back-off: 429 / timeout / transport error,
     * or a fail-closed path/cleartext rejection). Re-running the publish completes it (append-only,
     * idempotent). [reason] is a short diagnostic, never surfaced as a crash.
     */
    data class Failed(val reason: String) : ChatPublishOutcome
}

/**
 * The result of `ChatDirectoryService.readChatDirectory` (§11.6). Carries the **verified** descriptors
 * resolved to the latest version per chat-id, plus a count of rejected entries (diagnostics only —
 * never surfaced). A read never throws: a listing failure yields [entries] empty + [listingFailed].
 *
 * @property entries the verified [ChatDirectoryEntry] set, latest per chat-id (§11.5). In-memory,
 *   recomputed per read from the on-disk source of truth (no local cache this feature — §11.6; the Room
 *   cache + observable `Flow` is the UI feature's). All entries are [ChatKind.GROUP] (`dm` dropped).
 * @property rejectedCount how many entry files were dropped (wrong/absent community key, bad signature,
 *   malformed/truncated, content-hash mismatch, oversize, `dm`-kind, invalid access) — diagnostics only.
 * @property listingFailed true when the `PROPFIND Depth: 1` listing itself failed (429/timeout/error);
 *   the read returns whatever it could (typically empty) without crashing — retry next cycle.
 */
data class ChatDirectoryReadResult(
    val entries: List<ChatDirectoryEntry>,
    val rejectedCount: Int,
    val listingFailed: Boolean = false,
)
