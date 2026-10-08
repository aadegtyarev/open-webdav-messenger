package org.openwebdav.messenger.app

import android.content.Context
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.openwebdav.messenger.account.AccountMutationBarrier
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.CryptoFactory
import org.openwebdav.messenger.data.MessageStore
import org.openwebdav.messenger.data.MessengerDatabase
import org.openwebdav.messenger.directory.CredentialRotation
import org.openwebdav.messenger.directory.DirectoryFactory
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.identity.IdentityFactory
import org.openwebdav.messenger.identity.IdentityLoadResult
import org.openwebdav.messenger.keystore.ActiveCommunityStore
import org.openwebdav.messenger.keystore.ChatRegistry
import org.openwebdav.messenger.keystore.CommunityRegistry
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

    private val runtimeInstallLock = Any()

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

    /** Apply asynchronous enrichment only while the exact captured graph is still installed. */
    fun updateGraphIfCurrent(
        expectedGraph: RuntimeGraph,
        isContextCurrent: () -> Boolean,
        update: () -> Unit,
    ): Boolean =
        synchronized(runtimeInstallLock) {
            if (graph !== expectedGraph || !isContextCurrent()) return@synchronized false
            update()
            true
        }

    /** Suspend until process-start [initialize] has resolved the graph (used by the cold-start poll path). */
    suspend fun awaitReady() {
        ready.first { it }
    }

    /**
     * Process-start wiring with the given [Deps]. In production [AppContainer] passes [AndroidDeps] built
     * from its single shared factories; tests pass a JVM-backed seam. Called from the `Application` on a
     * background coroutine (Keystore/IO must not run on the main thread).
     */
    fun initialize(
        injected: Deps,
        afterGraphInstalled: (RuntimeGraph) -> Unit = {},
    ) {
        SyncRunner.install(SyncRunner { CycleOutcome(0, 0, backedOff = false) })
        deps = injected
        communityId = deps.activeCommunityId()
        graph = null
        activeChatIds = emptyList()
        rebuildFromStore()
        graph?.let { active ->
            runBlocking {
                (deps.joinedCommunityIds() + active.communityId).distinct().forEach { joinedId ->
                    active.store.recoverInterruptedOutgoing(joinedId)
                }
            }
            installAndSchedule(active, active.communityId)
            afterGraphInstalled(active)
        }
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
        roster: List<String>? = null,
        memberNames: Map<String, String> = emptyMap(),
        recipientReadiness: RecipientReadiness? = null,
    ) {
        // Onboarding can only run after the UI is shown, which waits on [ready] (i.e. after [initialize]
        // assigned [deps]); this guard makes the narrow process-start window explicit rather than letting
        // a lateinit access throw if a reconfigure ever raced ahead of warm-start.
        check(::deps.isInitialized) { "EngineWiring.reconfigure before initialize" }
        synchronized(runtimeInstallLock) {
            val selectedCommunityId = communityId
            this.communityId = selectedCommunityId
            val allChats = deps.communityChatIds(selectedCommunityId)
            val built = deps.buildGraph(config, chatId, communityName, chatKey, identity, selectedCommunityId)
            val g =
                if (roster == null) {
                    built
                } else {
                    RuntimeGraph(
                        engine = built.engine,
                        store = built.store,
                        envelope = built.envelope,
                        config = built.config,
                        chatId = built.chatId,
                        communityName = built.communityName,
                        chatKey = built.chatKey,
                        identity = built.identity,
                        senderIdentifier = built.senderIdentifier,
                        roster = roster,
                        communityId = built.communityId,
                        communityRuntimeKey = built.communityRuntimeKey,
                        initialRecipientReadiness = recipientReadiness ?: RecipientReadiness.Ready(roster),
                    ).also { it.memberNames = memberNames }
                }
            graph = g
            activeChatIds = allChats
            installAndSchedule(g, selectedCommunityId)
        }
    }

    /** Install a credential-updated runtime only if the community runtime that read it is still current. */
    fun reconfigureIfCurrent(
        expectedCommunityRuntimeKey: String,
        config: ConnectionConfig,
        communityId: String,
    ): Boolean =
        synchronized(runtimeInstallLock) {
            val activeGraph = graph ?: return@synchronized false
            if (activeGraph.communityRuntimeKey != expectedCommunityRuntimeKey || activeGraph.communityId != communityId) {
                return@synchronized false
            }
            val readiness =
                when (val current = activeGraph.recipientSnapshot()) {
                    RecipientReadiness.Loading ->
                        RecipientReadiness.Unavailable("Verified roster lookup needs retry after credential rotation")
                    else -> current
                }
            reconfigure(
                config = config,
                chatId = activeGraph.chatId,
                communityName = activeGraph.communityName,
                chatKey = activeGraph.chatKey,
                identity = activeGraph.identity,
                communityId = communityId,
                roster = activeGraph.roster,
                memberNames = activeGraph.memberNames,
                recipientReadiness = readiness,
            )
            true
        }

    /**
     * Switch the active send-path chat within the current community (e.g. from community chat to a DM).
     * The poll subscriptions (all community chats) stay unchanged; only the active [RuntimeGraph] is
     * replaced so the send path uses the correct [chatId], [chatKey], display name, and roster.
     */
    fun switchToChatIfCurrent(
        guard: RuntimeSelectionGuard,
        expectedSelectionRevision: Long,
        expectedGraph: RuntimeGraph,
        chatId: String,
        chatName: String,
        chatKey: ChatKey,
        roster: List<String>,
        memberNames: Map<String, String>,
        isCommunitySelected: () -> Boolean,
        recipientReadiness: RecipientReadiness = RecipientReadiness.Ready(roster),
        beforeInstall: () -> Unit = {},
    ): Boolean =
        guard.runIfCurrent(expectedSelectionRevision) {
            synchronized(runtimeInstallLock) {
                if (graph !== expectedGraph || !isCommunitySelected()) return@synchronized false
                beforeInstall()
                installChatLocked(expectedGraph, chatId, chatName, chatKey, roster, memberNames, recipientReadiness)
                true
            }
        }

    fun switchToChat(
        chatId: String,
        chatName: String,
        chatKey: ChatKey,
        roster: List<String>,
        memberNames: Map<String, String> = emptyMap(),
        recipientReadiness: RecipientReadiness = RecipientReadiness.Ready(roster),
    ) {
        synchronized(runtimeInstallLock) {
            val base = graph ?: return
            installChatLocked(base, chatId, chatName, chatKey, roster, memberNames, recipientReadiness)
        }
    }

    private fun installChatLocked(
        base: RuntimeGraph,
        chatId: String,
        chatName: String,
        chatKey: ChatKey,
        roster: List<String>,
        memberNames: Map<String, String>,
        recipientReadiness: RecipientReadiness,
    ) {
        val switched =
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
                communityId = base.communityId,
                communityRuntimeKey = base.communityRuntimeKey,
                initialRecipientReadiness = recipientReadiness,
            )
        switched.memberNames = memberNames
        graph = switched
        if (chatId !in activeChatIds) {
            activeChatIds = activeChatIds + chatId
            installAndSchedule(switched, base.communityId)
        }
    }

    private fun rebuildFromStore() {
        val selectedCommunityId = communityId
        val stored = deps.loadStoredConnection(selectedCommunityId) ?: return // no config → keep the no-op runner (benign clean cycle)
        val chatKey = deps.loadChatKey(stored.chatId) ?: return // key gone → stay no-op
        val identity = deps.loadIdentity() ?: return
        val allChats = deps.communityChatIds(selectedCommunityId)
        val g = deps.buildGraph(stored.config, stored.chatId, stored.communityName, chatKey, identity, selectedCommunityId)
        graph = g
        activeChatIds = allChats
    }

    private suspend fun pollOtherCommunities(
        activeCommunityId: String,
        activeGraph: RuntimeGraph,
    ): CycleOutcome {
        var combined = CycleOutcome(0, 0, backedOff = false)
        for (joinedId in deps.joinedCommunityIds().filter { it != activeCommunityId }) {
            try {
                val stored = deps.loadStoredConnection(joinedId) ?: continue
                val key = deps.loadChatKey(stored.chatId) ?: continue
                val graph = deps.buildGraph(stored.config, stored.chatId, stored.communityName, key, activeGraph.identity, joinedId)
                val subscriptions =
                    (deps.communityChatIds(joinedId) + stored.chatId).distinct().map(::ChatSubscription)
                val outcome = graph.engine.pollCycle(activeGraph.senderIdentifier, subscriptions, joinedId)
                combined =
                    combined.copy(
                        newCount = combined.newCount + outcome.newCount,
                        skippedCount = combined.skippedCount + outcome.skippedCount,
                        backedOff = combined.backedOff || outcome.backedOff,
                    )
            } catch (_: Exception) {
                combined = combined.copy(backedOff = true)
            }
        }
        return combined
    }

    private fun updateActiveCommunitySettings(
        expectedCommunityRuntimeKey: String,
        communityMinPollSeconds: Int?,
        retentionWindowDays: Int?,
    ) {
        synchronized(runtimeInstallLock) {
            if (graph?.communityRuntimeKey != expectedCommunityRuntimeKey) return
            val active = graph ?: return
            val currentFloor =
                communityMinPollSeconds
                    ?: org.openwebdav.messenger.ui.settings.UserSettings.pollFloorFor(active.communityId)
            val currentRetention =
                retentionWindowDays
                    ?: org.openwebdav.messenger.ui.settings.UserSettings.retentionDaysFor(active.communityId)
            org.openwebdav.messenger.ui.settings.UserSettings.setCommunityMetadata(
                active.communityId,
                currentFloor,
                currentRetention,
            )
            if (communityMinPollSeconds != null) deps.schedulePoll(communityMinPollSeconds)
        }
    }

    private fun installAndSchedule(
        g: RuntimeGraph,
        ownerCommunityId: String,
    ) {
        SyncRunner.install(
            object : SyncRunner {
                override suspend fun runOnce(): CycleOutcome =
                    AccountMutationBarrier.process.withCommunityCredentialRotation(ownerCommunityId) {
                        AccountMutationBarrier.process.withExclusive {
                            if (current()?.communityRuntimeKey != g.communityRuntimeKey) {
                                return@withExclusive CycleOutcome(0, 0, backedOff = false)
                            }
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
                                        val saved =
                                            AccountMutationBarrier.process.withStableAccount {
                                                if (!deps.saveRotatedConfig(newConfig, ownerCommunityId)) {
                                                    false
                                                } else {
                                                    reconfigureIfCurrent(
                                                        expectedCommunityRuntimeKey = g.communityRuntimeKey,
                                                        config = newConfig,
                                                        communityId = ownerCommunityId,
                                                    )
                                                    true
                                                }
                                            }
                                        if (saved) {
                                            // Delete remotely only after releasing the local account replacement gate.
                                            try {
                                                val delTransport = TransportFactory.create(newConfig)
                                                @Suppress("TooGenericExceptionCaught")
                                                delTransport.delete(credentialPath)
                                            } catch (_: Exception) {
                                                // best-effort — blob stays on disk, next cycle retries
                                            }
                                            return@withExclusive CycleOutcome(
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

                            // Publish the current member's directory entry so other members can resolve
                            // display names. Content-addressed (same entry → same file), idempotent, and
                            // best-effort: a failure leaves the hex-key fallback working as before.
                            try {
                                deps.publishDirectoryEntry(
                                    config = g.config,
                                    identity = g.identity,
                                    chatKey = g.chatKey,
                                    displayName = org.openwebdav.messenger.ui.settings.UserSettings.displayName,
                                )
                            } catch (_: Exception) {
                                // best-effort — directory publish failure is never a poll failure
                            }

                            // Discover new public group chats from the on-disk chat-directory.
                            try {
                                deps.discoverPublicChats()
                            } catch (_: Exception) {
                                // best-effort — retry next cycle
                            }

                            val subscriptions =
                                (deps.communityChatIds(ownerCommunityId) + g.chatId).distinct().map(::ChatSubscription)
                            val outcome = g.engine.pollCycle(g.senderIdentifier, subscriptions, ownerCommunityId)
                            val otherCommunities = pollOtherCommunities(ownerCommunityId, g)
                            val combinedOutcome =
                                outcome.copy(
                                    newCount = outcome.newCount + otherCommunities.newCount,
                                    skippedCount = outcome.skippedCount + otherCommunities.skippedCount,
                                    backedOff = outcome.backedOff || otherCommunities.backedOff,
                                )
                            updateActiveCommunitySettings(
                                expectedCommunityRuntimeKey = g.communityRuntimeKey,
                                communityMinPollSeconds = outcome.communityMinPollSeconds,
                                retentionWindowDays = outcome.retentionWindowDays,
                            )
                            combinedOutcome
                        }
                    }
            },
        )
        deps.schedulePoll()
    }

    /** The device-bound seam the wiring composes through — overridable in JVM tests. */
    internal interface Deps {
        fun loadStoredConnection(): StoredConnection?

        fun activeCommunityId(): String = "default"

        fun joinedCommunityIds(): List<String> = emptyList()

        fun loadStoredConnection(communityId: String): StoredConnection? = loadStoredConnection()

        fun loadChatKey(chatId: String): ChatKey?

        fun loadIdentity(): Identity?

        fun identityCrypto(): IdentityCrypto

        /** Read a raw WebDAV file at [path] using the [config], or null on any failure. */
        suspend fun readRawFile(
            config: ConnectionConfig,
            path: String,
        ): ByteArray?

        /** Save new connection credentials while retaining the stored community anchor. */
        fun saveRotatedConfig(
            newConfig: ConnectionConfig,
            communityId: String,
        ): Boolean

        fun buildGraph(
            config: ConnectionConfig,
            chatId: String,
            communityName: String,
            chatKey: ChatKey,
            identity: Identity,
            communityId: String,
        ): RuntimeGraph

        /** All chat-ids in the active community (for multi-chat poll subscriptions). */
        fun communityChatIds(communityId: String): List<String>

        suspend fun discoverPublicChats() {
            AppContainer.discoverPublicChats()
        }

        fun schedulePoll(communityMinPollSeconds: Int? = null)

        /**
         * Publish the current member's directory entry to the community `directory/` collection.
         * Best-effort: failure is caught silently — the hex-key fallback remains the graceful
         * degradation. Content-addressed, so re-publishing identical (identity, displayName,
         * versionCounter) bytes is idempotent (same file, no duplicate write).
         */
        suspend fun publishDirectoryEntry(
            config: ConnectionConfig,
            identity: Identity,
            chatKey: ChatKey,
            displayName: String,
        ) {
            // no-op default — JVM test doubles skip directory publishing
        }
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
    private val directoryFactory: DirectoryFactory,
) : EngineWiring.Deps {
    private val chatKeyStore by lazy { crypto.chatKeyStore(appContext) }

    override fun loadStoredConnection(): StoredConnection? = configStore.loadStored()

    override fun activeCommunityId(): String =
        ActiveCommunityStore(appContext).load(CommunityRegistry(appContext).all().firstOrNull()?.id ?: "default")

    override fun joinedCommunityIds(): List<String> = CommunityRegistry(appContext).all().map { it.id }

    override fun loadStoredConnection(communityId: String): StoredConnection? = configStore.loadStored(communityId)

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
        communityId: String,
    ): Boolean = configStore.saveRotatedConfig(newConfig, communityId)

    override fun communityChatIds(communityId: String): List<String> = chatRegistry.all(communityId).map { it.id }

    override fun buildGraph(
        config: ConnectionConfig,
        chatId: String,
        communityName: String,
        chatKey: ChatKey,
        identity: Identity,
        communityId: String,
    ): RuntimeGraph {
        val db = MessengerDatabase.get(appContext)
        val store = MessageStore(db.messageDao(), db.syncCursorDao(), communityId)
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
            communityId = communityId,
        )
    }

    override fun schedulePoll(communityMinPollSeconds: Int?) {
        val memberPref = org.openwebdav.messenger.ui.settings.UserSettings.pollIntervalSeconds.toLong()
        val communityFloor = (communityMinPollSeconds ?: 0).toLong()
        // Raw effective: user prefs + community floor, no artificial platform clamp.
        val rawEffective = maxOf(memberPref, communityFloor.coerceAtLeast(1))
        val workManagerFloorSeconds = PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS / 1000
        if (rawEffective < workManagerFloorSeconds) {
            FastPollManager.enable(appContext, WorkManager.getInstance(appContext), rawEffective)
        } else {
            FastPollManager.disable(appContext, WorkManager.getInstance(appContext))
            SyncScheduler.schedule(
                WorkManager.getInstance(appContext),
                SyncScheduler.effectiveIntervalSeconds(memberPref, communityMinPollSeconds),
            )
        }
    }

    override suspend fun publishDirectoryEntry(
        config: ConnectionConfig,
        identity: Identity,
        chatKey: ChatKey,
        displayName: String,
    ) {
        val service =
            directoryFactory.directoryService(
                baseUrl = config.baseUrl,
                username = config.username,
                appPassword = config.appPassword,
                communityRoot = config.chatRoot,
            )
        service.publishEntry(identity, displayName, versionCounter = 1, communityKey = chatKey)
    }
}
