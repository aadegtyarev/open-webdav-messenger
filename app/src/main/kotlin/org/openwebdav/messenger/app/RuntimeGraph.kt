package org.openwebdav.messenger.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.data.MessageStore
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.message.MessageEnvelope
import org.openwebdav.messenger.sync.ChatSubscription
import org.openwebdav.messenger.sync.SyncEngine
import org.openwebdav.messenger.transport.ConnectionConfig
import java.util.concurrent.atomic.AtomicLong

/**
 * The process-scoped, fully-composed graph for **one joined community chat** (`ui-chat-surface` arch note
 * Choice 1). [EngineWiring] builds exactly one of these from the stored connection config + chat key +
 * identity, and BOTH runtime paths read the **same** instance:
 *  - the poll path — the installed `SyncRunner` closes over [engine] `.pollCycle`;
 *  - the send path — the chat ViewModel builds a `TextMessage`, seals it via [envelope] under [chatKey],
 *    mints a §4 order-token, calls [engine] `.send(...)`, then persists the local echo via [store].
 *
 * Holding the one [engine] here (not letting each caller re-compose) is what makes the test-wiring-parity
 * requirement true: `relaunch_with_saved_config_reinstalls_runner` drives the same `SyncRunner.install`
 * path the production `Application` uses (arch note Choice 1 risk note).
 *
 * @property chatKey the random per-chat key (in memory only — never logged, never on disk).
 * @property identity the on-device identity (signs sent messages; secret keys never linger past use).
 * @property senderIdentifier the stable member identifier (the hex of the identity's Ed25519 public key)
 *   reused for both `OrderToken.build` and the `SyncEngine.send`/`pollCycle` member key.
 */
internal class RuntimeGraph(
    val engine: SyncEngine,
    val store: MessageStore,
    val envelope: MessageEnvelope,
    val config: ConnectionConfig,
    val chatId: String,
    val communityName: String,
    val chatKey: ChatKey,
    val identity: Identity,
    val senderIdentifier: String,
    /** All member identifiers in this chat (for change-entry fan-out). */
    val roster: List<String> = listOf(senderIdentifier),
) {
    /** Signing-pubkey-hex → display name (from directory). Updated asynchronously. */
    private val _memberNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val memberNamesFlow: StateFlow<Map<String, String>> = _memberNames

    var memberNames: Map<String, String>
        get() = _memberNames.value
        set(value) {
            _memberNames.value = value
        }

    /** Error from the last member-name refresh, or null. Observed by the chat feed for banners. */
    private val _memberNamesError = MutableStateFlow<String?>(null)
    val memberNamesError: StateFlow<String?> = _memberNamesError

    /** Publish a transient member-names error — cleared on the next successful refresh. */
    fun setMemberNamesError(message: String?) {
        _memberNamesError.value = message
    }

    /** Epoch millis of the last successful poll cycle (0 = never synced). Delegates to the engine's process-scoped state. */
    val lastSyncTime: StateFlow<Long> = engine.lastSyncTime

    /**
     * Trigger an immediate poll cycle for the current chat. Suspends until the cycle completes
     * (engine.pollCycle is a suspend function); the engine updates [lastSyncTime] on success.
     */
    suspend fun requestSync() {
        engine.pollCycle(senderIdentifier, listOf(ChatSubscription(chatId)))
    }

    /**
     * Per-process, strictly-increasing per-sender sequence for the §4 order-token. The token orders the
     * feed for display only (dedup is by §2 message-id), so a per-process counter is sufficient — the
     * leading wall-clock millis dominates ordering across relaunches.
     */
    private val seq = AtomicLong(0)

    /** The next per-sender sequence value for an order-token. */
    fun nextSeq(): Long = seq.incrementAndGet()
}
