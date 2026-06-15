package org.openwebdav.messenger.protocol

/**
 * Chat-root-relative paths for the **generation-2** folder layout in
 * `docs/protocol/webdav-layout.md` §1 (shared per-chat log + per-member change index).
 * All paths are relative to the chat-root (which lives in connection config, never on disk, §1.1).
 *
 * Generation 2 (sync feature, 2026-06-04) replaces the v1 per-recipient `inbox/` tree with:
 *  - `log/` — the ONE shared per-chat message log (one content-addressed file per message, §1.2/§3).
 *  - `changes/<member-index-id>/` — one change-index folder per member; a sender drops a tiny
 *    change-entry into each other member's index on send (§9.1/§9.2).
 *
 * The `member-index-id` derivation is byte-identical to the v1 `recipient-inbox-id`
 * ([MessageId.inboxId]) — the same hash, pointed at `changes/` instead of `inbox/` (arch note §1.2).
 */
internal object ChatPaths {
    /** §1: `meta/` collection (chat descriptor / roster — populated by a later feature). */
    const val META = "meta"

    /**
     * §1 (generation 1, superseded): the v1 `inbox/` parent collection. Generation 2 no longer
     * writes here — the shared `log/` + per-member `changes/` replace it (§1.2). Retained only as a
     * pure path-string helper for the transport-feature tests that predate the rework (those tests
     * exercise WebDAV verbs over an arbitrary path; they do not assert the layout generation).
     */
    const val INBOX = "inbox"

    /**
     * §1.2 (generation 1, superseded): mint a v1 `inbox/<recipient-inbox-id>/` path. The
     * generation-2 app uses [changeIndex] (`changes/`) instead; this is retained only for the
     * predating transport-feature tests (see [INBOX]).
     */
    fun inbox(
        recipientIdentifier: String,
        chatId: String,
    ): String = "$INBOX/${MessageId.inboxId(recipientIdentifier, chatId)}"

    /**
     * §2/§3 (generation 1, superseded): the content-addressed message path under a v1 inbox path.
     * Retained for the predating transport-feature tests; the generation-2 app uses [message]
     * (with chatId) to write into the per-chat `log/<chatId>/`.
     */
    fun v1Message(
        inboxPath: String,
        orderToken: String,
        fileBytes: ByteArray,
    ): String = "$inboxPath/${MessageId.messageId(orderToken, fileBytes)}"

    /** §1.2: `log/` — the log parent directory. Use [logDir] for per-chat subfolders. */
    const val LOG = "log"

    /** Read receipts: `read/<member-id>/<chat-id>/` — cursor marker for latest read message. */
    fun readReceiptDir(
        memberIdentifier: String,
        chatId: String,
    ): String = "$READ/${MessageId.inboxId(memberIdentifier, chatId)}"

    /** A single read receipt file: `read/<member-id>/<chat-id>/<order-token>` — marks read up to this cursor. */
    fun readReceiptFile(
        memberIdentifier: String,
        chatId: String,
        orderToken: String,
    ): String = "${readReceiptDir(memberIdentifier, chatId)}/$orderToken"

    /** §1.2: `log/<chatId>/` — the per-chat message log subfolder. */
    fun logDir(chatId: String): String = "$LOG/$chatId"

    /** §1: `changes/` — parent of all per-member change indices. */
    const val CHANGES = "changes"

    /** Read receipts parent directory. */
    private const val READ = "read"

    /** §1.2: a member's change-index folder `changes/<member-index-id>/`. */
    fun changeIndex(
        memberIdentifier: String,
        chatId: String,
    ): String = "$CHANGES/${MessageId.inboxId(memberIdentifier, chatId)}"

    /**
     * §1.2/§9.2: a single change-entry path inside a member's change index. The entry name encodes
     * the cursor coordinate it advances the member to (`chat-tag "~" order-token`, §9.2); it carries
     * no body. Written non-conditionally — two senders produce distinct entries, never a rewrite.
     */
    fun changeEntry(
        changeIndexPath: String,
        chatId: String,
        orderToken: String,
    ): String = "$changeIndexPath/${ChangeEntry.name(chatId, orderToken)}"

    /**
     * §2/§3: the content-addressed message path inside the per-chat `log/<chatId>/`.
     * The file name **is** the message-id = `order-token "~" content-hash(fileBytes)`.
     *
     * @param fileBytes the exact framed envelope bytes that will be PUT (the hash is over these).
     */
    fun message(
        chatId: String,
        orderToken: String,
        fileBytes: ByteArray,
    ): String = "${logDir(chatId)}/${MessageId.messageId(orderToken, fileBytes)}"
}
