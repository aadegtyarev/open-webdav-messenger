package org.openwebdav.messenger.sync

import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.data.MessageStore
import org.openwebdav.messenger.message.MessageEnvelope
import org.openwebdav.messenger.message.ParseResult
import org.openwebdav.messenger.protocol.ChangeEntry
import org.openwebdav.messenger.protocol.ChatPaths
import org.openwebdav.messenger.protocol.Envelope
import org.openwebdav.messenger.protocol.MessageId
import org.openwebdav.messenger.transport.InboxEntry
import org.openwebdav.messenger.transport.ReadResult
import org.openwebdav.messenger.transport.WebDavResult
import org.openwebdav.messenger.transport.WebDavTransport

/**
 * The §9.3 poll-cycle read path (`docs/protocol/webdav-layout.md`): read this member's change index in
 * one cheap `PROPFIND Depth: 1`, resolve which joined chats changed + their high-water coordinate
 * (the **max** order-token per chat-tag, NOT last-write-wins — arch note §35/§51), then per changed
 * chat fetch only the new `log/` entries, validate each (§3 hash + §5.1 AEAD + §8 parse/verify),
 * persist with dedup, and **advance the cursor only over entries successfully persisted** (§9.3 — the
 * one invariant that must not be wrong, arch note §32).
 *
 * Orchestration only: it composes [WebDavTransport] (verbs), [MessageEnvelope] (open/parse/verify),
 * and [MessageStore] (persist + cursor). The chat key is loaded **once per chat** via [ChatKeyProvider]
 * and reused for every envelope in that chat — Argon2id is never re-run per message (scenario 7).
 */
internal class PollReader(
    private val transport: WebDavTransport,
    private val envelope: MessageEnvelope,
    private val store: MessageStore,
    private val keyProvider: ChatKeyProvider,
    private val clock: () -> Long,
) {
    /**
     * Run one read cycle for [memberIdentifier] over its [subscriptions] (joined chats). Reads the
     * change index, then drains each changed chat. Returns the aggregate [CycleOutcome]; never throws.
     */
    suspend fun cycle(
        memberIdentifier: String,
        subscriptions: List<ChatSubscription>,
    ): CycleOutcome {
        // §9.3 step 1: one PROPFIND on this member's change index → MAX order-token per chat-tag. On a
        // listing failure (tampered/missing index) the tag is absent here and the per-chat full-log
        // fallback kicks in (§9.3 step 4 / §3 flat-trust degradation) — lossless, slower.
        val coordinates = maxCoordinatesByTag(memberIdentifier, subscriptions)
        // §9.3 step 2: the shared `log/` is ONE chat-root resource (§1: one chat-root == one chat). List
        // it ONCE per cycle and reuse the listing for every subscription's drain (review finding 2) — the
        // generation-2 win is precisely NOT re-listing the whole log K times per cycle. The per-chat
        // cursor/target filtering stays in drainChat. Cardinality note: `selectPending` does not scope by
        // chat-id; it relies on the §5.1 AEAD key-mismatch to discard a foreign chat's entries (the
        // damage/fallback path of §3). With one chat per chat-root (§1) the listing is the same for all
        // subscriptions; a future multi-chat-per-root layout would need a per-chat log scope here.
        var acc = CycleOutcome(newCount = 0, skippedCount = 0, backedOff = false)
        // Decide which chats need a drain before listing the log: a chat with a known coordinate that is
        // not newer than its stored cursor needs no fetch (the steady-state no-op). Only if at least one
        // chat needs draining do we pay the single shared-log PROPFIND — preserving the prior no-op
        // optimization while still listing the log at most ONCE per cycle (review finding 2).
        val toDrain = subscriptions.filter { needsDrain(it, coordinates[it.chatTag]) }
        if (toDrain.isEmpty()) return acc
        for (sub in toDrain) {
            val logEntries =
                when (val listed = transport.list(ChatPaths.logDir(sub.chatId))) {
                    is WebDavResult.Success -> listed.value
                    else -> return backedOffOutcome(listed)
                }
            val target = coordinates[sub.chatTag]
            acc += drainChat(sub, target, logEntries)
            if (acc.backedOff) break // stop the cycle on a transport back-off; resume next run (§9.3)
            // Check read receipts from other members and mark our messages as READ.
            checkReadReceipts(memberIdentifier, sub.chatId)
        }
        return acc
    }

    /**
     * Whether [sub] needs a `log/` drain this cycle: a known change-index coordinate that is not newer
     * than the stored cursor means nothing changed (skip the fetch); a null coordinate (no change entry
     * / damaged index) always drains via the full-log fallback (§9.3 step 4).
     */
    private suspend fun needsDrain(
        sub: ChatSubscription,
        targetCoordinate: String?,
    ): Boolean = targetCoordinate == null || targetCoordinate > store.cursorFor(sub.chatId)

    /**
     * §9.3 step 1: list `changes/<member-index-id>/`, parse each entry, and reduce to the MAX
     * order-token per chat-tag. A malformed/foreign entry is ignored (§9.2). Returns an empty map on a
     * listing failure — the caller then uses the per-chat full-log fallback (§9.3 step 4).
     */
    private suspend fun maxCoordinatesByTag(
        memberIdentifier: String,
        subscriptions: List<ChatSubscription>,
    ): Map<String, String> {
        if (subscriptions.isEmpty()) return emptyMap()
        // All subscriptions share the same chat-root credential, so the change-index folder id is
        // per (member, chat). A member's index spans all its chats; we list it per chat-id (the
        // member-index-id is chat-scoped, §1.2) and merge.
        val maxByTag = HashMap<String, String>()
        for (sub in subscriptions) {
            val indexPath = ChatPaths.changeIndex(memberIdentifier, sub.chatId)
            val listed = transport.list(indexPath)
            if (listed !is WebDavResult.Success) continue // damaged/missing → full-log fallback for this chat
            for (entry in listed.value) {
                val parsed = ChangeEntry.parse(entry.name) ?: continue
                val existing = maxByTag[parsed.chatTag]
                if (existing == null || parsed.orderToken > existing) {
                    maxByTag[parsed.chatTag] = parsed.orderToken
                }
            }
        }
        return maxByTag
    }

    /**
     * Drain one chat against the already-fetched [logEntries] (listed once per cycle, review finding 2):
     * if [targetCoordinate] is null (no change entry / damaged index), scan the whole `log/` against the
     * stored cursor (§9.3 step 4 fallback); otherwise only fetch entries up to the coordinate. Fetches,
     * validates, persists, and advances the cursor over the longest contiguous persisted prefix (§9.3).
     */
    private suspend fun drainChat(
        sub: ChatSubscription,
        targetCoordinate: String?,
        logEntries: List<InboxEntry>,
    ): CycleOutcome {
        // The caller (cycle) already filtered to chats that need a drain (needsDrain); re-read the
        // cursor as the selection floor.
        val cursor = store.cursorFor(sub.chatId)
        val pending = selectPending(logEntries, cursor, targetCoordinate)
        return fetchAndPersist(sub.chatId, pending)
    }

    /**
     * Select the §2 message names whose order-token is strictly greater than [cursor] and (when a
     * change-index coordinate is known) ≤ [targetCoordinate], sorted ascending by the §2 name (§4 /
     * §9.3 step 2). Collections and non-message names are filtered out.
     */
    private fun selectPending(
        entries: List<InboxEntry>,
        cursor: String,
        targetCoordinate: String?,
    ): List<String> =
        entries
            .asSequence()
            .filter { !it.isCollection }
            // Validate FULL §2 well-formedness (order-token 29 chars [0-9a-z-] + content-hash 32 chars
            // [a-z2-7]) before the name's order-token enters the cursor comparison (review finding 5).
            // A foreign/malformed name in the shared log/ is dropped here, never trusted as a coordinate.
            .filter { MessageId.isWellFormedMessageId(it.name) }
            .mapNotNull { entry -> MessageId.splitMessageId(entry.name)?.let { entry.name to it.first } }
            .filter { (_, orderToken) -> orderToken > cursor }
            .filter { (_, orderToken) -> targetCoordinate == null || orderToken <= targetCoordinate }
            .sortedBy { (name, _) -> name }
            .map { (name, _) -> name }
            .toList()

    /**
     * Fetch each pending name in order, validate, persist with dedup, and advance the cursor over the
     * **longest contiguous resolved prefix** (§9.3, review finding 1). An entry is *resolved* when it is
     * either persisted (or a dedup no-op) or **permanently Rejected** (AEAD/signature fail / unsupported
     * codec = a forged/tampered file a member can plant under the flat-trust model, §3) — both let the
     * cursor move past their coordinate. A **transient NotReady** (incomplete upload / §3 hash-mismatch)
     * that opens a *forward coordinate gap* (its order-token is newer than everything resolved so far)
     * STOPS the cursor: that coordinate is retried next cycle, never skipped past by a later complete
     * entry (the §9.3 no-loss invariant). A NotReady at a coordinate already reached (a same-coordinate
     * sibling of a resolved entry) opens no gap and does not block. A transport back-off STOPS the drain
     * with the cursor left before the unfetched entries. The chat key is loaded ONCE here (scenario 7).
     */
    private suspend fun fetchAndPersist(
        chatId: String,
        pending: List<String>,
    ): CycleOutcome {
        if (pending.isEmpty()) return CycleOutcome(0, 0, backedOff = false)
        // §scenario 7: load the ChatKey ONCE for this chat and reuse it for every envelope below —
        // Argon2id is not re-run per message. A null key means the chat is not openable this cycle.
        val chatKey = keyProvider.keyFor(chatId) ?: return CycleOutcome(0, pending.size, backedOff = false)
        var newCount = 0
        var skipped = 0
        // Track the longest contiguous resolved prefix; freeze the cursor at the first NotReady-only gap.
        val prefix = CursorPrefix(store.cursorFor(chatId))
        for (name in pending) {
            val orderToken = MessageId.splitMessageId(name)?.first ?: continue
            when (val step = fetchOne(chatId, name, chatKey)) {
                is FetchStep.Persisted -> {
                    if (step.inserted) newCount++
                    // Persisted/dedup or a permanent Rejected both RESOLVE the coordinate: the cursor may
                    // advance over it. A Rejected (forged/tampered/unsupported codec) must NOT wedge the
                    // cursor under flat trust — a member can plant a forged file (§3 / review finding 1).
                    prefix.record(orderToken, resolved = true)
                }
                FetchStep.Rejected -> {
                    skipped++
                    prefix.record(orderToken, resolved = true)
                }
                // A transient NotReady (incomplete upload / §3 hash-mismatch) does NOT resolve its
                // coordinate. If that coordinate has no resolved sibling, it is a forward gap → the cursor
                // freezes before it, so it is retried next cycle, never skipped past (§9.3 no-loss).
                FetchStep.NotReady -> {
                    skipped++
                    prefix.record(orderToken, resolved = false)
                }
                FetchStep.BackOff -> {
                    store.advanceCursor(chatId, prefix.finish())
                    return CycleOutcome(newCount, skipped, backedOff = true)
                }
            }
        }
        store.advanceCursor(chatId, prefix.finish())
        return CycleOutcome(newCount, skipped, backedOff = false)
    }

    /** Fetch + validate one log entry into a typed [FetchStep]; the chat key is loaded once per chat. */
    private suspend fun fetchOne(
        chatId: String,
        name: String,
        chatKey: ChatKey,
    ): FetchStep {
        val path = "${ChatPaths.logDir(chatId)}/$name"
        return when (val read = transport.read(path, name)) {
            is WebDavResult.Success ->
                when (val r = read.value) {
                    is ReadResult.Ready -> validateAndStore(name, r.blob, r.codecId, chatKey)
                    ReadResult.NotReady -> FetchStep.NotReady // §3 incomplete / hash-mismatch → retry next cycle
                }
            else -> FetchStep.BackOff // 429 / timeout / transport error → stop, resume next run (§9.3)
        }
    }

    /**
     * AEAD-open + §8 parse/verify the §5 [blob] under [chatKey] and persist on success. The §5.1 AAD is
     * the 8-byte header; the transport exposes the REAL on-disk [codecId] ([ReadResult.Ready.codecId]),
     * so the header is rebuilt faithfully ([Envelope.frame]) instead of assuming `0x00` (review finding
     * 4). Codec dispatch (decompress if `codec-id = 0x01 deflate`) now lives inside [MessageEnvelope.open]
     * — this method no longer manually rejects a non-none codec; the envelope layer handles it. AEAD /
     * decompression / signature failures all surface as [FetchStep.Rejected] (forged/tampered — the cycle
     * continues, scenario 3).
     */
    private suspend fun validateAndStore(
        name: String,
        blob: ByteArray,
        codecId: Byte,
        chatKey: ChatKey,
    ): FetchStep {
        val envelopeBytes = Envelope.frame(codecId, blob)
        return when (val parsed = envelope.open(envelopeBytes, chatKey)) {
            is ParseResult.Parsed -> {
                val orderToken = MessageId.splitMessageId(name)?.first ?: return FetchStep.Rejected
                val inserted = store.persist(name, orderToken, parsed.message, clock())
                FetchStep.Persisted(orderToken, inserted)
            }
            is ParseResult.Rejected -> FetchStep.Rejected
        }
    }

    /** Check read receipts from other members and mark our messages as READ. */
    private suspend fun checkReadReceipts(
        myId: String,
        chatId: String,
    ) {
        // In MVP, without a roster, this is a no-op.
        // TODO: iterate over roster members excluding self, check their read receipts.
    }

    private fun backedOffOutcome(result: WebDavResult<*>): CycleOutcome {
        // A failed log listing (429/timeout/error) is a back-off: nothing fetched, cursor unmoved.
        check(result !is WebDavResult.Success)
        return CycleOutcome(0, 0, backedOff = true)
    }

    private operator fun CycleOutcome.plus(other: CycleOutcome): CycleOutcome =
        CycleOutcome(
            newCount = newCount + other.newCount,
            skippedCount = skippedCount + other.skippedCount,
            backedOff = backedOff || other.backedOff,
        )
}
