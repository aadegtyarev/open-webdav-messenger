package org.openwebdav.messenger.app

import android.content.Context
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.CryptoFactory
import org.openwebdav.messenger.data.MessageStore
import org.openwebdav.messenger.data.MessengerDatabase
import org.openwebdav.messenger.directory.CredentialRotation
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.identity.IdentityFactory
import org.openwebdav.messenger.identity.IdentityLoadResult
import org.openwebdav.messenger.keystore.ChatRegistry
import org.openwebdav.messenger.keystore.ConnectionConfigStore
import org.openwebdav.messenger.keystore.StoredConnection
import org.openwebdav.messenger.message.MessageEnvelope
import org.openwebdav.messenger.protocol.Hex
import org.openwebdav.messenger.sync.ChatSubscription
import org.openwebdav.messenger.sync.CycleOutcome
import org.openwebdav.messenger.sync.FastPollManager
import org.openwebdav.messenger.sync.RetentionPruner
import org.openwebdav.messenger.sync.SyncEngine
import org.openwebdav.messenger.sync.SyncRunner
import org.openwebdav.messenger.sync.SyncScheduler
import org.openwebdav.messenger.transport.ConnectionConfig
import org.openwebdav.messenger.transport.TransportFactory
import org.openwebdav.messenger.transport.WebDavResult

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
            )
        graph!!.memberNames = memberNames
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
                    // Pre-poll credential rotation check: if the host rotated the WebDAV credential,
                    // a blob at meta/credentials/<mySignPubHex> exists on disk. Download it, open it
                    // with our box keypair, verify the host's Ed25519 signature, and auto-replace the
                    // local config so the poll cycle below uses the new credential.
                    val mySignPubHex = g.senderIdentifier
                    val credentialPath = "meta/credentials/$mySignPubHex"
                    val idCrypto = deps.identityCrypto()
                    try {
                        val blob = deps.readRawFile(g.config, credentialPath)
                        if (blob != null) {
                            val newConfig =
                                CredentialRotation.openForMember(
                                    blob = blob,
                                    identity = g.identity,
                                    identityCrypto = idCrypto,
                                )
                            if (newConfig != null) {
                                // Apply the new credential: persist it and rebuild the engine so
                                // the poll cycle below (and all future cycles) use the new URL.
                                if (deps.saveRotatedConfig(newConfig, g.chatId, g.communityName)) {
                                    // Delete the credential blob from disk (best-effort — if it
                                    // stays, the next cycle re-opens and no-ops idempotently).
                                    try {
                                        val delTransport = TransportFactory.create(newConfig)
                                        @Suppress("TooGenericExceptionCaught")
                                        delTransport.delete(credentialPath)
                                    } catch (_: Exception) {
                                        // best-effort — blob stays on disk, next cycle retries
                                    }
                                    // Rebuild the engine with the new config. The current graph
                                    // fields (chatId, communityName, chatKey, identity) stay the same;
                                    // only the ConnectionConfig changes.
                                    reconfigure(
                                        config = newConfig,
                                        chatId = g.chatId,
                                        communityName = g.communityName,
                                        chatKey = g.chatKey,
                                        identity = g.identity,
                                        communityId = communityId,
                                    )
                                    // Return immediately — the engine was rebuilt with the new
                                    // credential; the next scheduled poll will use it.
                                    return CycleOutcome(
                                        newCount = 0,
                                        skippedCount = 0,
                                        backedOff = false,
                                    )
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // Credential check failure is never a poll failure — the next cycle retries.
                    }

                    val outcome = g.engine.pollCycle(g.senderIdentifier, subscriptions)
                    // Cache the community floor for the settings UI and reschedule.
                    if (outcome.communityMinPollSeconds != null) {
                        org.openwebdav.messenger.ui.settings.UserSettings.communityMinPollSeconds =
                            outcome.communityMinPollSeconds
                        deps.schedulePoll(outcome.communityMinPollSeconds)
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

        fun identityCrypto(): IdentityCrypto

        /** Read a raw WebDAV file at [path] using the [config], or null on any failure. */
        suspend fun readRawFile(
            config: ConnectionConfig,
            path: String,
        ): ByteArray?

        /**
         * Save [newConfig] to the config store with the existing [chatId] and [communityName].
         * Returns `true` on success.
         */
        fun saveRotatedConfig(
            newConfig: ConnectionConfig,
            chatId: String,
            communityName: String,
        ): Boolean

        fun buildGraph(
            config: ConnectionConfig,
            chatId: String,
            communityName: String,
            chatKey: ChatKey,
            identity: Identity,
        ): RuntimeGraph

        /** All chat-ids in the active community (for multi-chat poll subscriptions). */
        fun communityChatIds(communityId: String): List<String>

        fun schedulePoll(communityMinPollSeconds: Int? = null)
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

    override fun identityCrypto(): IdentityCrypto = identityFactory.identityCrypto()

    override suspend fun readRawFile(
        config: ConnectionConfig,
        path: String,
    ): ByteArray? {
        val transport = TransportFactory.create(config)
        return when (val result = transport.readRaw(path)) {
            is WebDavResult.Success -> result.value
            else -> null
        }
    }

    override fun saveRotatedConfig(
        newConfig: ConnectionConfig,
        chatId: String,
        communityName: String,
    ): Boolean {
        val stored = configStore.loadStored() ?: return false
        configStore.save(newConfig, stored.chatId, stored.communityName)
        return true
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
        // The community-metadata reader: best-effort read of meta/community.json. The host's
        // Ed25519 public key is embedded in the file itself (last 32 bytes, unsigned — accepted
        // under flat-trust SC11), so no out-of-band host-key resolution is needed. This works
        // for both the host device (identity IS the host) and non-host members.
        val communityMetadataReader: suspend () -> CommunityMetadata? = {
            CommunityMetadata.read(transport, idCrypto)
        }
        val communityFloorReader: suspend () -> Int? = {
            communityMetadataReader()?.minPollIntervalSeconds
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

    override fun schedulePoll(communityMinPollSeconds: Int?) {
        val memberPref = org.openwebdav.messenger.ui.settings.UserSettings.pollIntervalSeconds.toLong()
        val effective = SyncScheduler.effectiveIntervalSeconds(memberPref, communityMinPollSeconds)
        // WorkManager clamps every periodic request to 15 minutes. Anything below that needs
        // the foreground service (which shows a persistent notification — Android requirement).
        val workManagerFloorSeconds = PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS / 1000
        if (effective < workManagerFloorSeconds) {
            FastPollManager.enable(appContext, WorkManager.getInstance(appContext), effective)
        } else {
            FastPollManager.disable(appContext, WorkManager.getInstance(appContext))
            SyncScheduler.schedule(WorkManager.getInstance(appContext), effective)
        }
    }
}
