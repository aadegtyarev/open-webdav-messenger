package org.openwebdav.messenger.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.openwebdav.messenger.data.MessageStore
import org.openwebdav.messenger.message.MessageEnvelope
import org.openwebdav.messenger.transport.WebDavTransport
import kotlin.time.Duration.Companion.days

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
    private val pruner: RetentionPruner? = null,
    private val onNewMessages: (suspend (outcome: CycleOutcome) -> Unit)? = null,
    private val communityFloorReader: (suspend () -> Int?)? = null,
    private val retentionWindowReader: (suspend () -> Int?)? = null,
) {
    private val _lastSyncTime = MutableStateFlow(0L)

    /** Epoch millis of the last successful poll cycle (0 if never synced). Updated after a non-backed-off cycle. */
    val lastSyncTime: StateFlow<Long> = _lastSyncTime.asStateFlow()

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
     *
     * Before the poll: reads the community floor (best-effort, via [communityFloorReader]). If the reader
     * is null or fails, the floor is absent — the caller falls back to the default.
     *
     * After a successful cycle (no back-off): runs retention pruning over each subscribed chat's
     * `log/` and `changes/` entries (§1.4). Then invokes [onNewMessages] if new messages were found.
     *
     * Pruning and notifications are best-effort and never affect the poll outcome.
     */
    suspend fun pollCycle(
        memberIdentifier: String,
        subscriptions: List<ChatSubscription>,
    ): CycleOutcome {
        val communityFloor = readCommunityFloor()
        val retentionDays = readRetentionWindow()
        if (retentionDays != null) {
            val days = retentionDays
            pruner?.window = days.days
        }
        val outcome = pollReader.cycle(memberIdentifier, subscriptions)
        val result =
            outcome.copy(
                communityMinPollSeconds = communityFloor,
                retentionWindowDays = retentionDays,
            )
        if (!outcome.backedOff) {
            _lastSyncTime.value = clock()
            subscriptions.forEach { sub ->
                pruner?.pruneChat(sub.chatId)
            }
            if (outcome.newCount > 0) {
                try {
                    onNewMessages?.invoke(result)
                } catch (_: Exception) {
                    // Notification failure is never a poll-cycle failure.
                }
            }
        }
        return result
    }

    /** Read the community floor, or null if unavailable. Best-effort — never throws. */
    private suspend fun readCommunityFloor(): Int? {
        return try {
            communityFloorReader?.invoke()
        } catch (_: Exception) {
            null
        }
    }

    /** Read the community retention window, or null if unavailable. Best-effort — never throws. */
    private suspend fun readRetentionWindow(): Int? {
        return try {
            retentionWindowReader?.invoke()
        } catch (_: Exception) {
            null
        }
    }
}
