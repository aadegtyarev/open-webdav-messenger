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
import org.openwebdav.messenger.keystore.ChatRegistry
import org.openwebdav.messenger.keystore.ConnectionConfigStore
import org.openwebdav.messenger.keystore.StoredConnection
import org.openwebdav.messenger.message.MessageEnvelope
import org.openwebdav.messenger.protocol.Hex
import org.openwebdav.messenger.sync.ChatSubscription
import org.openwebdav.messenger.sync.CycleOutcome
import org.openwebdav.messenger.sync.RetentionPruner
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
    private var communityId: String = "default"

    @Volatile
    private var activeChatIds: List<String> = emptyList()

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

    /** The composed graph for the active chat, or `null` if no config exists yet (no chat joined). */
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
     * them) so the rebuild does not re-read them. [communityId] identifies the community for multi-chat
     * subscription enumeration.
     */
    fun reconfigure(
        config: ConnectionConfig,
        chatId: String,
        communityName: String,
        chatKey: ChatKey,
        identity: Identity,
        communityId: String = "default",
    ) {
        // Onboarding can only run after the UI is shown, which waits on [ready] (i.e. after [initialize]
        // assigned [deps]); this guard makes the narrow process-start window explicit rather than letting
        // a lateinit access throw if a reconfigure ever raced ahead of warm-start.
        check(::deps.isInitialized) { "EngineWiring.reconfigure before initialize" }
        this.communityId = communityId
        val allChats = deps.communityChatIds(communityId)
        val g = deps.buildGraph(config, chatId, communityName, chatKey, identity)
        graph = g
        activeChatIds = allChats
        installAndSchedule(g)
    }

    /**
     * Switch the active send-path chat within the current community (e.g. from community chat to a DM).
     * The poll subscriptions (all community chats) stay unchanged; only the active [RuntimeGraph] is
     * replaced so the send path uses the correct [chatId], [chatKey], display name, and roster.
     */
    fun switchToChat(
        chatId: String,
        chatName: String,
        chatKey: ChatKey,
        roster: List<String>,
        memberNames: Map<String, String> = emptyMap(),
    ) {
        val base = graph ?: return
        graph =
            RuntimeGraph(
                engine = base.engine,
                store = base.store,
                envelope = base.envelope,
                config = base.config,
                chatId = chatId,
                communityName = chatName,
                chatKey = chatKey,
                identity = base.identity,
                senderIdentifier = base.senderIdentifier,
                roster = roster,
                memberNames = memberNames,
            )
        // If this chat is not yet in the poll subscription list, add it and reinstall.
        if (chatId !in activeChatIds) {
            activeChatIds = activeChatIds + chatId
            installAndSchedule(graph!!)
        }
    }

    private fun rebuildFromStore() {
        val stored = deps.loadStoredConnection() ?: return // no config → keep the no-op runner (benign clean cycle)
        val chatKey = deps.loadChatKey(stored.chatId) ?: return // key gone → stay no-op
        val identity = deps.loadIdentity() ?: return
        val allChats = deps.communityChatIds(communityId)
        val g = deps.buildGraph(stored.config, stored.chatId, stored.communityName, chatKey, identity)
        graph = g
        activeChatIds = allChats
        installAndSchedule(g)
    }

    private fun installAndSchedule(g: RuntimeGraph) {
        val subscriptions =
            if (activeChatIds.isNotEmpty()) {
                activeChatIds.map { ChatSubscription(it) }
            } else {
                listOf(ChatSubscription(g.chatId))
            }
        SyncRunner.install(
            object : SyncRunner {
                override suspend fun runOnce(): CycleOutcome {
                    val outcome = g.engine.pollCycle(g.senderIdentifier, subscriptions)
                    // Cache the community floor for the settings UI and reschedule.
                    if (outcome.communityMinPollMinutes != null) {
                        org.openwebdav.messenger.ui.settings.UserSettings.communityMinPollMinutes =
                            outcome.communityMinPollMinutes
                        deps.schedulePoll(outcome.communityMinPollMinutes)
                    }
                    // Cache the community retention window for the settings UI.
                    if (outcome.retentionWindowDays != null) {
                        org.openwebdav.messenger.ui.settings.UserSettings.communityRetentionWindowDays =
                            outcome.retentionWindowDays
                    }
                    return outcome
                }
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

        /** All chat-ids in the active community (for multi-chat poll subscriptions). */
        fun communityChatIds(communityId: String): List<String>

        fun schedulePoll(communityMinPollMinutes: Int? = null)
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
    private val chatRegistry: ChatRegistry,
) : EngineWiring.Deps {
    private val chatKeyStore by lazy { crypto.chatKeyStore(appContext) }

    override fun loadStoredConnection(): StoredConnection? = configStore.loadStored()

    override fun loadChatKey(chatId: String): ChatKey? = chatKeyStore.load(chatId)

    override fun loadIdentity(): Identity? =
        when (val result = identityFactory.identityStore(appContext).load()) {
            is IdentityLoadResult.Loaded -> result.identity
            else -> null // None (nothing joined yet) or Unrecoverable — onboarding surfaces it
        }

    override fun communityChatIds(communityId: String): List<String> = chatRegistry.all(communityId).map { it.id }

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
        val transport = TransportFactory.create(config)
        val idCrypto = identityFactory.identityCrypto()
        // The community-metadata reader: best-effort read of meta/community.json signed by the host.
        // For now, verify against the local identity's signing key (correct when this device IS
        // the host; for non-host members, the signature verification fails and the reader returns
        // null → fallback to the default floor). A full host-key resolution (from roster/directory)
        // is deferred to when those features are complete.
        val hostPubKey = identity.copySignPublic()
        val communityMetadataReader: suspend () -> CommunityMetadata? = {
            CommunityMetadata.read(transport, idCrypto, hostPubKey)
        }
        val communityFloorReader: suspend () -> Int? = {
            communityMetadataReader()?.minPollIntervalMinutes
        }
        val retentionWindowReader: suspend () -> Int? = {
            communityMetadataReader()?.retentionWindowDays
        }
        // Notification callback: show a notification when new messages arrive during background poll.
        val ctx = appContext
        val onNewMessages: suspend (org.openwebdav.messenger.sync.CycleOutcome) -> Unit = { outcome ->
            if (outcome.newCount > 0) {
                NotificationHelper.showCycleNotification(ctx, communityName, outcome.newCount)
            }
        }
        val engine =
            SyncEngine(
                transport = transport,
                envelope = envelope,
                store = store,
                keyProvider = { requested -> chatKeyStore.load(requested) ?: if (requested == chatId) chatKey else null },
                pruner = RetentionPruner(transport = transport),
                onNewMessages = onNewMessages,
                communityFloorReader = communityFloorReader,
                retentionWindowReader = retentionWindowReader,
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

    override fun schedulePoll(communityMinPollMinutes: Int?) {
        val memberPref = org.openwebdav.messenger.ui.settings.UserSettings.pollIntervalMinutes.toLong()
        val effective = SyncScheduler.effectiveIntervalMinutes(memberPref, communityMinPollMinutes)
        SyncScheduler.schedule(WorkManager.getInstance(appContext), effective)
    }
}
