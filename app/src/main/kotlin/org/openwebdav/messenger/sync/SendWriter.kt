package org.openwebdav.messenger.sync

import org.openwebdav.messenger.protocol.ChatPaths
import org.openwebdav.messenger.transport.WebDavResult
import org.openwebdav.messenger.transport.WebDavTransport

/**
 * The §9.1 send write path (`docs/protocol/webdav-layout.md`): write the sealed envelope **once** to
 * the shared `log/`, then drop one tiny change entry into **each other member's** change index. Pure
 * orchestration over [WebDavTransport] — no crypto, no SQL (arch note Variant A).
 *
 * Properties this path upholds:
 *  - **One log write regardless of member count** (the generation-2 win over v1's N copies, §9.1).
 *  - **Idempotent / append-only** — the log path is content-addressed (§2/§3) and every change entry
 *    is a new cursor-addressed file (§9.2); a re-run re-`PUT`s the same files as no-ops. Both PUTs are
 *    non-conditional (§6).
 *  - **Partial-failure tolerant** — a `429`/timeout on any single PUT is reported, not raised; the log
 *    write may be durable while some notifies are pending, and re-running completes them (§9.1, no
 *    multi-file transaction to half-commit).
 */
internal class SendWriter(private val transport: WebDavTransport) {
    /**
     * Send a sealed [envelopeBytes] (its §4 [orderToken]) to [chatId]'s log and notify every member in
     * [otherMembers] (the roster MINUS the sender — the sender does not notify itself, §9.1). The
     * collections are ensured first via idempotent `MKCOL` (§6).
     */
    suspend fun send(
        chatId: String,
        orderToken: String,
        envelopeBytes: ByteArray,
        otherMembers: List<String>,
    ): SendOutcome {
        // §6: ensure the chat root exists as a WebDAV collection before writing anything into it.
        // Some providers (e.g. Yandex.Disk) report folders created via their web UI as 404 on
        // PROPFIND and 409 on child MKCOL — a root MKCOL with a trailing slash fixes this.
        if (!ensure("")) {
            return SendOutcome(logWritten = false, notifiedMembers = 0, pendingMembers = otherMembers.size)
        }
        val logWritten = writeLog(chatId, orderToken, envelopeBytes)
        if (!logWritten) {
            // No log entry on disk → there is nothing to notify about yet; report all members pending
            // so a re-run writes the log then the notifies (idempotent). Never write change entries
            // pointing at a coordinate that is not in log/ (would advance a reader past a missing file).
            return SendOutcome(logWritten = false, notifiedMembers = 0, pendingMembers = otherMembers.size)
        }
        // §6/§9.1: ensure the shared `changes/` parent ONCE per send, not per member (review finding 3).
        // If the parent MKCOL backs off, no member can be notified this send → all pending, re-run later.
        if (otherMembers.isNotEmpty() && !ensure(ChatPaths.CHANGES)) {
            return SendOutcome(logWritten = true, notifiedMembers = 0, pendingMembers = otherMembers.size)
        }
        var notified = 0
        var pending = 0
        for (member in otherMembers) {
            if (notifyMember(member, chatId, orderToken)) notified++ else pending++
        }
        return SendOutcome(logWritten = true, notifiedMembers = notified, pendingMembers = pending)
    }

    /** §9.1 step 1: ensure `log/` then PUT the content-addressed envelope once (non-conditional, §6). */
    private suspend fun writeLog(
        chatId: String,
        orderToken: String,
        envelopeBytes: ByteArray,
    ): Boolean {
        if (!ensure(ChatPaths.LOG)) return false
        val path = ChatPaths.message(orderToken, envelopeBytes)
        return transport.write(path, envelopeBytes) is WebDavResult.Success
    }

    /**
     * §9.1 step 2: ensure this member's `changes/<member-index-id>/` then PUT one tiny change entry
     * (a 1-byte marker body — the meaning is the name, §9.2). Non-conditional (§6/§9.2). The shared
     * `changes/` parent is ensured once by the caller (review finding 3); only the per-member folder
     * is ensured here.
     */
    private suspend fun notifyMember(
        member: String,
        chatId: String,
        orderToken: String,
    ): Boolean {
        val indexPath = ChatPaths.changeIndex(member, chatId)
        if (!ensure(indexPath)) return false
        val entryPath = ChatPaths.changeEntry(indexPath, chatId, orderToken)
        return transport.write(entryPath, CHANGE_ENTRY_BODY) is WebDavResult.Success
    }

    /** Idempotent `MKCOL` (§6): an already-exists (405/301) is success inside the transport. */
    private suspend fun ensure(path: String): Boolean = transport.ensureCollection(path) is WebDavResult.Success

    private companion object {
        /**
         * A change entry's meaning is entirely in its file name (§9.2); the body carries nothing. A
         * single byte is written so providers that reject a zero-length PUT still accept it.
         */
        val CHANGE_ENTRY_BODY = byteArrayOf(0x00)
    }
}
