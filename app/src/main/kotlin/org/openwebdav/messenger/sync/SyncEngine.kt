package org.openwebdav.messenger.sync

import org.openwebdav.messenger.data.MessageStore
import org.openwebdav.messenger.message.MessageEnvelope
import org.openwebdav.messenger.transport.WebDavTransport

/**
 * The sync orchestration seam (`docs/features/sync_plan.md` → Contracts; arch note Variant A
 * `sync/` orchestrates over `transport/` + `protocol/` + `crypto/`/`message/` + `data/`).
 *
 * Two operations, both pure orchestration (no HTTP, no SQL, no crypto in this class — it composes the
 * lower layers, so it stays inside the file/function-size limits):
 *  - [send] — write a sealed envelope once to the shared `log/`, then notify each member's change
 *    index (§9.1). Idempotent on the §2 message-id; partial-failure tolerant.
 *  - [pollCycle] — read this member's change index, fetch only the new `log/` entries for changed
 *    chats, validate + dedup + persist, advance the cursor only past persisted entries (§9.3).
 *
 * `internal` because its constructor consumes the internal transport; the public entry points are the
 * [send]/[pollCycle] results ([SendOutcome] / [CycleOutcome]) and the WorkManager [SyncWorker].
 */
internal class SyncEngine(
    transport: WebDavTransport,
    envelope: MessageEnvelope,
    store: MessageStore,
    keyProvider: ChatKeyProvider,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val sendWriter = SendWriter(transport)
    private val pollReader = PollReader(transport, envelope, store, keyProvider, clock)

    /**
     * §9.1: send an already-sealed-and-signed [envelopeBytes] (its §4 [orderToken]) to [chatId]. The
     * roster [allMembers] is supplied by the caller (out-of-band/config, §1.3); the sender is removed
     * so it does not notify itself (§9.1). Never throws — returns a typed [SendOutcome].
     */
    suspend fun send(
        chatId: String,
        orderToken: String,
        envelopeBytes: ByteArray,
        allMembers: List<String>,
        senderIdentifier: String,
    ): SendOutcome {
        val others = allMembers.filter { it != senderIdentifier }
        return sendWriter.send(chatId, orderToken, envelopeBytes, others)
    }

    /**
     * §9.3: run one poll cycle for [memberIdentifier] over its joined [subscriptions]. Reads the change
     * index, fetches new envelopes, validates/dedups/persists, advances cursors. Never throws —
     * returns a typed [CycleOutcome] (the [SyncWorker] maps `backedOff` to a retry).
     */
    suspend fun pollCycle(
        memberIdentifier: String,
        subscriptions: List<ChatSubscription>,
    ): CycleOutcome = pollReader.cycle(memberIdentifier, subscriptions)
}
