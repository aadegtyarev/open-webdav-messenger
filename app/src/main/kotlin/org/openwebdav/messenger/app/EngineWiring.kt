package org.openwebdav.messenger.app

import android.content.Context
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.CryptoFactory
import org.openwebdav.messenger.data.MessageStore
import org.openwebdav.messenger.data.MessengerDatabase
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.identity.IdentityFactory
import org.openwebdav.messenger.identity.IdentityLoadResult
import org.openwebdav.messenger.keystore.ConnectionConfigStore
import org.openwebdav.messenger.keystore.StoredConnection
import org.openwebdav.messenger.message.MessageEnvelope
import org.openwebdav.messenger.protocol.Hex
import org.openwebdav.messenger.sync.ChatSubscription
import org.openwebdav.messenger.sync.CycleOutcome
import org.openwebdav.messenger.sync.SyncEngine
import org.openwebdav.messenger.sync.SyncRunner
import org.openwebdav.messenger.sync.SyncScheduler
import org.openwebdav.messenger.transport.ConnectionConfig
import org.openwebdav.messenger.transport.TransportFactory

/**
 * The app's single composition root (`ui-chat-surface` arch note Choice 1, RECOMMENDED Option A),
 * invoked from `OpenWebDavMessengerApp.onCreate()`. It owns the process-scoped [RuntimeGraph] and is the
 * one place that composes transport + identity + crypto/message + data into a live [SyncEngine] for BOTH
 * the poll path (the installed [SyncRunner]) and the send path (the chat ViewModel reads [current]).
 *
 * Lifecycle:
 *  - [initialize] — at process start, read the persisted [ConnectionConfig] + chat key. If both exist,
 *    build the graph, `install` a real runner over `engine.pollCycle`, and `schedule` the poll. If absent,
 *    leave the default **no-op** runner (a scheduled poll before any config is a benign clean cycle — the
 *    runner must NOT be installed too early over a null config; arch note behavioral risk).
 *  - [reconfigure] — after the onboarding flow first persists a config (owner create / member join), build
 *    the graph and re-`install` + re-`schedule` so the live send path and the poll path share one engine.
 *
 * The heavy, device-bound construction (native crypto, Keystore, WorkManager) sits behind a [Deps] seam so
 * the wiring logic — "no config ⇒ stay no-op; config ⇒ build one graph + install the real runner" — is
 * JVM-testable (`relaunch_with_saved_config_reinstalls_runner` / `poll_before_any_config_is_benign…`)
 * against the SAME `SyncRunner.install` path the production `Application` uses. `internal` (it composes the
 * `internal` [SyncEngine] / [ConnectionConfig]).
 */
internal object EngineWiring {
    @Volatile
    private var graph: RuntimeGraph? = null

    @Volatile
    private lateinit var deps: Deps

    private val _ready = MutableStateFlow(false)

    /**
     * Process-start readiness — `false` until [initialize] has read the persisted config and resolved the
     * start graph (or confirmed none). The UI collects this and shows a brief loading state instead of
     * racing the async warm-start; once `true`, [current] reflects the resolved graph (fixes the
     * start-destination race — arch note Choice 1 behavioral risk).
     */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** The composed graph for the joined chat, or `null` if no config exists yet (no chat joined). */
    fun current(): RuntimeGraph? = graph

    /** Suspend until process-start [initialize] has resolved the graph (used by the cold-start poll path). */
    suspend fun awaitReady() {
        ready.first { it }
    }

    /**
     * Process-start wiring with the given [Deps]. In production [AppContainer] passes [AndroidDeps] built
     * from its single shared factories; tests pass a JVM-backed seam. Called from the `Application` on a
     * background coroutine (Keystore/IO must not run on the main thread).
     */
    fun initialize(injected: Deps) {
        deps = injected
        graph = null
        rebuildFromStore()
        _ready.value = true
    }

    /**
     * Rebuild the graph after the onboarding flow persisted a new config + stored the chat key. The
     * [identity] and raw-imported [chatKey] are passed in (the onboarding ViewModel already loaded/minted
     * them) so the rebuild does not re-read them.
     */
    fun reconfigure(
        config: ConnectionConfig,
        chatId: String,
        communityName: String,
        chatKey: ChatKey,
        identity: Identity,
    ) {
        // Onboarding can only run after the UI is shown, which waits on [ready] (i.e. after [initialize]
        // assigned [deps]); this guard makes the narrow process-start window explicit rather than letting
        // a lateinit access throw if a reconfigure ever raced ahead of warm-start.
        check(::deps.isInitialized) { "EngineWiring.reconfigure before initialize" }
        graph = deps.buildGraph(config, chatId, communityName, chatKey, identity)
        installAndSchedule(graph!!)
    }

    private fun rebuildFromStore() {
        val stored = deps.loadStoredConnection() ?: return // no config → keep the no-op runner (benign clean cycle)
        val chatKey = deps.loadChatKey(stored.chatId) ?: return // key gone → stay no-op
        val identity = deps.loadIdentity() ?: return
        graph = deps.buildGraph(stored.config, stored.chatId, stored.communityName, chatKey, identity)
        installAndSchedule(graph!!)
    }

    private fun installAndSchedule(graph: RuntimeGraph) {
        val subscriptions = listOf(ChatSubscription(graph.chatId))
        SyncRunner.install(
            object : SyncRunner {
                override suspend fun runOnce(): CycleOutcome = graph.engine.pollCycle(graph.senderIdentifier, subscriptions)
            },
        )
        deps.schedulePoll()
    }

    /** The device-bound seam the wiring composes through — overridable in JVM tests. */
    internal interface Deps {
        fun loadStoredConnection(): StoredConnection?

        fun loadChatKey(chatId: String): ChatKey?

        fun loadIdentity(): Identity?

        fun buildGraph(
            config: ConnectionConfig,
            chatId: String,
            communityName: String,
            chatKey: ChatKey,
            identity: Identity,
        ): RuntimeGraph

        fun schedulePoll()
    }
}

/**
 * Production [EngineWiring.Deps]: native-backed crypto/identity factories, the Keystore-wrapped config +
 * chat-key stores, the Room message store, and the WorkManager scheduler. Constructs the one shared
 * [SyncEngine] both runtime paths use.
 *
 * The [crypto] / [identityFactory] / [configStore] are passed in so this reuses [AppContainer]'s single
 * process-scoped instances rather than re-constructing its own (AppContainer is the single holder).
 */
internal class AndroidDeps(
    private val appContext: Context,
    private val crypto: CryptoFactory,
    private val identityFactory: IdentityFactory,
    private val configStore: ConnectionConfigStore,
) : EngineWiring.Deps {
    override fun loadStoredConnection(): StoredConnection? = configStore.load()

    override fun loadChatKey(chatId: String): ChatKey? = crypto.chatKeyStore(appContext).load(chatId)

    override fun loadIdentity(): Identity? =
        when (val result = identityFactory.identityStore(appContext).load()) {
            is IdentityLoadResult.Loaded -> result.identity
            else -> null // None (nothing joined yet) or Unrecoverable — onboarding surfaces it
        }

    override fun buildGraph(
        config: ConnectionConfig,
        chatId: String,
        communityName: String,
        chatKey: ChatKey,
        identity: Identity,
    ): RuntimeGraph {
        val db = MessengerDatabase.get(appContext)
        val store = MessageStore(db.messageDao(), db.syncCursorDao())
        val envelope = MessageEnvelope.create(crypto.messageCrypto(), identityFactory.identityCrypto())
        val engine =
            SyncEngine(
                transport = TransportFactory.create(config),
                envelope = envelope,
                store = store,
                keyProvider = { requested -> if (requested == chatId) chatKey else null },
            )
        return RuntimeGraph(
            engine = engine,
            store = store,
            envelope = envelope,
            config = config,
            chatId = chatId,
            communityName = communityName,
            chatKey = chatKey,
            identity = identity,
            senderIdentifier = Hex.encode(identity.copySignPublic()),
        )
    }

    override fun schedulePoll() {
        SyncScheduler.schedule(WorkManager.getInstance(appContext))
    }
}
