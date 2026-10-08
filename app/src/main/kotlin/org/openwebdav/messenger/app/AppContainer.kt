package org.openwebdav.messenger.app

import android.content.Context
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.openwebdav.messenger.account.AccountMutationBarrier
import org.openwebdav.messenger.chatdirectory.ChatAccess
import org.openwebdav.messenger.chatdirectory.ChatDirectoryFactory
import org.openwebdav.messenger.chatdirectory.ChatKind
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.CryptoFactory
import org.openwebdav.messenger.crypto.KeySources
import org.openwebdav.messenger.data.MessageStore
import org.openwebdav.messenger.data.MessengerDatabase
import org.openwebdav.messenger.directory.CredentialRotation
import org.openwebdav.messenger.directory.DirectoryEntry
import org.openwebdav.messenger.directory.DirectoryFactory
import org.openwebdav.messenger.directory.DirectoryReadResult
import org.openwebdav.messenger.directory.RemoteChatProvisioner
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.identity.IdentityFactory
import org.openwebdav.messenger.invite.InviteCodec
import org.openwebdav.messenger.invite.InviteToken
import org.openwebdav.messenger.keystore.ActiveCommunityStore
import org.openwebdav.messenger.keystore.ChatKeyStorePort
import org.openwebdav.messenger.keystore.ChatRegistry
import org.openwebdav.messenger.keystore.CommunityRegistry
import org.openwebdav.messenger.keystore.ConnectionConfigStore
import org.openwebdav.messenger.keystore.StoredConnection
import org.openwebdav.messenger.protocol.Base32
import org.openwebdav.messenger.protocol.Hex
import org.openwebdav.messenger.sync.FastPollManager
import org.openwebdav.messenger.sync.SyncScheduler
import org.openwebdav.messenger.transport.ConnectionConfig
import org.openwebdav.messenger.transport.TransportFactory
import org.openwebdav.messenger.transport.WebDavResult
import org.openwebdav.messenger.ui.settings.UserSettings
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The process-scoped holder the UI layer reaches for the composed app services (`ui-chat-surface` arch
 * note Choice 1/4). It exposes the [OnboardingService] (owner-create / member-join) and the [EngineWiring]
 * (the live engine for the feed/send path). One instance per process, built lazily on first use from the
 * application [Context]; the `Application` calls [warmStart] to wire the engine at process start.
 *
 * Keeping the device-bound factories here (one `CryptoFactory`, one `IdentityFactory`) matches the
 * existing factory-per-process convention and gives the ViewModels a single, testable entry point.
 */
internal object AppContainer {
    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var currentCommunityId: String = "default"

    private val crypto by lazy { CryptoFactory() }
    private val identityFactory by lazy { IdentityFactory() }
    private val configStore by lazy { ConnectionConfigStore(requireContext()) }
    private val activeCommunityStore by lazy { ActiveCommunityStore(requireContext()) }
    private val communityRegistry by lazy { CommunityRegistry(requireContext()) }
    private val chatRegistry by lazy { ChatRegistry(requireContext()) }
    private val directoryFactory by lazy { DirectoryFactory() }
    private val chatDirectoryFactory by lazy { ChatDirectoryFactory() }
    private val warmStarted = AtomicBoolean(false)
    private val communityPolicyCoordinator = CommunityPolicyCoordinator()
    private val rosterCommitCoordinator = RosterCommitCoordinator()
    private val runtimeSelectionGuard = RuntimeSelectionGuard(rosterCommitCoordinator)
    private val chatOpenRequestCoordinator = ChatOpenRequestCoordinator(rosterCommitCoordinator)
    private val productionRosterCache by lazy {
        VerifiedRosterCache(VerifiedRosterCacheStore(requireContext()), rosterCommitCoordinator)
    }

    @Volatile
    private var rosterCacheTestOverride: VerifiedRosterCache? = null

    private val rosterCache: VerifiedRosterCache get() = rosterCacheTestOverride ?: productionRosterCache

    @Volatile
    private var chatOpenTestSeam: ChatOpenTestSeam? = null

    @Volatile
    private var credentialRotationTestSeam: CredentialRotationTestSeam? = null

    internal data class CredentialRotationTestSeam(
        val loadStored: (String) -> StoredConnection?,
        val loadChatKey: (String) -> ChatKey?,
        val readDirectory: suspend (StoredConnection, ChatKey) -> List<DirectoryEntry>,
        val writeCredential: suspend (String, ConnectionConfig, String, ByteArray) -> Boolean,
        val saveStored: (String, StoredConnection) -> Boolean,
        val onCommunitySelected: (String) -> Unit = {},
    )

    private data class CredentialRotationSnapshot(
        val ownerCommunityId: String,
        val graph: RuntimeGraph,
        val stored: StoredConnection,
        val communityKey: ChatKey,
        val replacementGeneration: Long,
        val testSeam: CredentialRotationTestSeam?,
    )

    internal data class ChatOpenTestSeam(
        val loadChatKey: (String) -> ChatKey?,
        val loadStored: (String) -> StoredConnection?,
        val readDirectory: suspend (StoredConnection) -> DirectoryReadResult,
        val onRosterEnrichmentCompleted: (() -> Unit)? = null,
        val cachePersistence: VerifiedRosterCachePersistence? = null,
        val chatKind: ((String, String) -> String?)? = null,
        val onGeneralRosterCompleted: (() -> Unit)? = null,
    )

    private val groupChatOpenCoordinator by lazy {
        GroupChatOpenCoordinator(
            selectionGuard = runtimeSelectionGuard,
            requestCoordinator = chatOpenRequestCoordinator,
            currentCommunityId = { currentCommunityId },
            loadChatKey = { chatId ->
                val seam = chatOpenTestSeam
                if (seam != null) seam.loadChatKey(chatId) else crypto.chatKeyStore(requireContext()).load(chatId)
            },
            activateCommunity = { communityId, requestToken ->
                switchToCommunityExclusive(communityId, requestToken, refreshGeneralRoster = false)
            },
            currentGraph = ::runtimeGraph,
        )
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Process-start readiness — `false` until [warmStart] has resolved the start graph. The UI collects
     * this to show a brief loading state instead of racing the async warm-start (start-destination race fix).
     */
    val ready: StateFlow<Boolean> get() = EngineWiring.ready

    /** The current community ID (for multi-chat enumeration). */
    val activeCommunityId: String get() = currentCommunityId

    internal fun configureChatOpenTestSeam(
        communityId: String,
        seam: ChatOpenTestSeam,
    ) {
        currentCommunityId = communityId
        chatOpenTestSeam = seam
        rosterCacheTestOverride = VerifiedRosterCache(seam.cachePersistence ?: EmptyRosterCachePersistence, rosterCommitCoordinator)
    }

    internal fun clearChatOpenTestSeam() {
        chatOpenTestSeam = null
        rosterCacheTestOverride = null
        currentCommunityId = "default"
    }

    internal fun configureCredentialRotationTestSeam(
        communityId: String,
        seam: CredentialRotationTestSeam,
    ) {
        currentCommunityId = communityId
        credentialRotationTestSeam = seam
    }

    internal fun clearCredentialRotationTestSeam() {
        credentialRotationTestSeam = null
        currentCommunityId = "default"
    }

    /** Bind the application context (idempotent). Called from `Application.onCreate()` before [warmStart]. */
    fun bind(context: Context) {
        if (appContext == null) {
            val ctx = context.applicationContext
            appContext = ctx
            UserSettings.init(ctx)
        }
    }

    /**
     * Process-start engine wiring (reads the persisted config off the main thread; arch note Choice 1).
     * Idempotent — a second call (e.g. a cold-start `SyncWorker` that beat the `Application`'s launch) is a
     * no-op, so the runner is installed exactly once before either path reads it.
     */
    fun warmStart() {
        if (warmStarted.compareAndSet(false, true)) {
            val registeredCommunities = communityRegistry.all()
            currentCommunityId = activeCommunityStore.load(registeredCommunities.firstOrNull()?.id ?: "default")
            UserSettings.migrateLegacyCommunitySettings(currentCommunityId, registeredCommunities.size == 1)
            UserSettings.selectCommunity(currentCommunityId)
            // Open Room for persisted accounts so repair finishes before a runtime is published.
            if (registeredCommunities.isNotEmpty() || configStore.hasAny()) {
                MessengerDatabase.get(requireContext()).openHelper.writableDatabase
            }
            EngineWiring.initialize(
                AndroidDeps(requireContext(), crypto, identityFactory, configStore, chatRegistry, directoryFactory),
                afterGraphInstalled = ::prepareGeneralRoster,
            )
        }
    }

    /**
     * Ensure process-start wiring has run, then suspend until it has resolved the graph. The cold-start
     * [org.openwebdav.messenger.sync.SyncWorker] calls this before reading the installed runner so a poll
     * in a freshly-started process does not silently no-op over the default runner (review finding 2).
     */
    suspend fun ensureWarmStarted() {
        warmStart()
        EngineWiring.awaitReady()
    }

    internal fun invalidateRosterCaches() = rosterCache.invalidateAll()

    /** Rebuild after account replacement; clear again to fence work started during restore. */
    fun rebuildAfterRestore() {
        rosterCache.invalidateAll()
        rebuildStoredRuntime(requireGraph = true)
    }

    /** Reinstall the previous runtime after rollback; the cache stays cleared across replacement. */
    fun restorePreviousRuntime() {
        rosterCache.invalidateAll()
        rebuildStoredRuntime(requireGraph = communityRegistry.all().isNotEmpty())
    }

    private fun rebuildStoredRuntime(requireGraph: Boolean) {
        communityPolicyCoordinator.reset()
        val fallback = communityRegistry.all().firstOrNull()?.id ?: "default"
        currentCommunityId = activeCommunityStore.load(fallback)
        UserSettings.selectCommunity(currentCommunityId)
        EngineWiring.initialize(
            AndroidDeps(requireContext(), crypto, identityFactory, configStore, chatRegistry, directoryFactory),
            afterGraphInstalled = ::prepareGeneralRoster,
        )
        if (requireGraph) check(runtimeGraph() != null) { "Backup contains no usable community" }
    }

    /** The composed onboarding service (owner-create / member-join). */
    fun onboarding(): OnboardingService = OnboardingService(productionOnboardingDeps())

    /**
     * Group chat creation within the current community.
     * Generates a random key, derives a chat-id from BLAKE2b(key bytes + community-id),
     * stores the key, registers the chat, and switches to it.
     * Returns the new chat-id on success, or `null` if the graph is absent.
     */
    suspend fun createGroupChat(
        name: String,
        communityId: String = currentCommunityId,
        access: ChatAccess = ChatAccess.PUBLIC,
        requestToken: ChatOpenRequestCoordinator.Token = beginChatOpenRequest(),
    ): String? {
        if (!chatOpenRequestCoordinator.isCurrent(requestToken)) return null
        val expectedRuntimeKey = runtimeGraph()?.communityRuntimeKey ?: return null
        return createGroupInSelectedCommunity(
            communityId = communityId,
            activeCommunityId = currentCommunityId,
            isRuntimeCurrent = { runtimeGraph()?.communityRuntimeKey == expectedRuntimeKey },
            isRequestCurrent = { chatOpenRequestCoordinator.isCurrent(requestToken) },
            isSelectedContextCurrent = { context -> isCurrentGroupContext(context, requestToken) },
            activateCommunity = { selected ->
                AccountMutationBarrier.process.withStableAccount {
                    if (!chatOpenRequestCoordinator.isCurrent(requestToken)) {
                        false
                    } else {
                        switchToCommunityExclusive(selected, requestToken, refreshGeneralRoster = false)
                    }
                }
            },
            resolveContext = { selectedId -> resolveSelectedGroupContext(selectedId, requestToken) },
            create = { context -> createGroupChatForContext(name, access, context, requestToken) },
            open = { context, chatId ->
                openGroupChatExclusive(chatId, name, context.communityId, context.selectionRevision, requestToken)
            },
        )
    }

    private fun resolveSelectedGroupContext(
        communityId: String,
        requestToken: ChatOpenRequestCoordinator.Token,
    ): SelectedCommunityGroupContext? {
        var selectedContext: SelectedCommunityGroupContext? = null
        val captured =
            chatOpenRequestCoordinator.runIfCurrent(requestToken) {
                selectedContext =
                    selectedCommunityGroupContext(
                        communityId = communityId,
                        activeCommunityId = currentCommunityId,
                        stored = configStore.loadStored(communityId),
                        graph = runtimeGraph(),
                        selectionRevision = runtimeSelectionGuard.current(),
                    )
                selectedContext != null
            }
        return selectedContext.takeIf { captured }
    }

    private fun isCurrentGroupContext(
        context: SelectedCommunityGroupContext,
        requestToken: ChatOpenRequestCoordinator.Token,
    ): Boolean {
        if (!chatOpenRequestCoordinator.isCurrent(requestToken)) return false
        val graph = runtimeGraph() ?: return false
        return currentCommunityId == context.communityId &&
            runtimeSelectionGuard.isCurrent(context.selectionRevision) &&
            graph === context.graph &&
            graph.communityId == context.communityId &&
            graph.communityRuntimeKey == context.graph.communityRuntimeKey
    }

    private suspend fun createGroupChatForContext(
        name: String,
        access: ChatAccess,
        context: SelectedCommunityGroupContext,
        requestToken: ChatOpenRequestCoordinator.Token,
    ): String? {
        var createdChatId: String? = null
        var rawChatId: ByteArray? = null
        val stored = context.stored
        val inserted =
            AccountMutationBarrier.process.withStableAccount {
                chatOpenRequestCoordinator.runIfCurrent(requestToken) {
                    if (!isCurrentGroupContext(context, requestToken)) return@runIfCurrent false
                    val keySources = crypto.keySources()
                    val chatKey =
                        if (access == ChatAccess.PUBLIC) {
                            crypto.chatKeyStore(requireContext()).load(stored.chatId) ?: return@runIfCurrent false
                        } else {
                            keySources.newRandomKey()
                        }
                    val nonce = keySources.newRandomKey().copyBytes().take(8).toByteArray()
                    val hash =
                        crypto.nativeCrypto().genericHash(
                            "owdm/group-chat/v1".toByteArray(Charsets.UTF_8) +
                                byteArrayOf(0x1F) + nonce + context.communityId.toByteArray(Charsets.UTF_8) +
                                name.toByteArray(Charsets.UTF_8),
                            16,
                        )
                    val chatId = Hex.encode(hash)
                    crypto.chatKeyStore(requireContext()).store(chatId, chatKey)
                    chatRegistry.add(context.communityId, ChatRegistry.Entry(chatId, name, "group"))
                    createdChatId = chatId
                    rawChatId = hash
                    true
                }
            }
        if (!inserted) return null
        val chatId = createdChatId ?: return null
        if (access == ChatAccess.PUBLIC) {
            if (!isCurrentGroupContext(context, requestToken)) return null
            if (!publishPublicGroup(stored, context.graph, rawChatId ?: return null, name)) return null
        }
        return chatId
    }

    private suspend fun publishPublicGroup(
        stored: StoredConnection,
        graph: RuntimeGraph,
        rawChatId: ByteArray,
        title: String,
    ): Boolean =
        bestEffortGroupPublication {
            val service =
                chatDirectoryFactory.chatDirectoryService(
                    baseUrl = stored.config.baseUrl,
                    username = stored.config.username,
                    appPassword = stored.config.appPassword,
                    communityRoot = stored.config.chatRoot,
                )
            val communityKey = crypto.chatKeyStore(requireContext()).load(stored.chatId) ?: return@bestEffortGroupPublication false
            service.publishChatEntry(
                identity = graph.identity,
                chatId = rawChatId,
                kind = ChatKind.GROUP,
                access = ChatAccess.PUBLIC,
                title = title,
                versionCounter = 1,
                communityKey = communityKey,
            )
            true
        }

    /**
     * Open an existing group chat by [chatId]. Loads the key, loads the roster from the directory,
     * and switches the active send-path to this chat.
     */
    suspend fun openGroupChat(
        chatId: String,
        chatName: String,
        communityId: String = currentCommunityId,
        expectedSelectionRevision: Long? = null,
        requestToken: ChatOpenRequestCoordinator.Token = beginChatOpenRequest(),
    ): Boolean {
        if (!chatOpenRequestCoordinator.isCurrent(requestToken)) return false
        val expectedRuntimeKey = runtimeGraph()?.communityRuntimeKey ?: return false
        val plan =
            AccountMutationBarrier.process.withStableAccount {
                if (!chatOpenRequestCoordinator.isCurrent(requestToken)) return@withStableAccount null
                if (runtimeGraph()?.communityRuntimeKey != expectedRuntimeKey) return@withStableAccount null
                prepareGroupChatOpen(chatId, chatName, communityId, expectedSelectionRevision, requestToken)
            } ?: return false
        return installPreparedGroupChat(plan)
    }

    private suspend fun openGroupChatExclusive(
        chatId: String,
        chatName: String,
        communityId: String,
        expectedSelectionRevision: Long?,
        requestToken: ChatOpenRequestCoordinator.Token,
    ): Boolean =
        AccountMutationBarrier.process.withStableAccount {
            prepareGroupChatOpen(chatId, chatName, communityId, expectedSelectionRevision, requestToken)
                ?.let { installPreparedGroupChat(it) }
                ?: false
        }

    private data class PreparedGroupChatOpen(
        val selection: GroupChatOpenCoordinator.Plan,
        val stored: StoredConnection?,
        val communityKey: ChatKey?,
        val kind: String,
    )

    private fun prepareGroupChatOpen(
        chatId: String,
        chatName: String,
        communityId: String,
        expectedSelectionRevision: Long?,
        requestToken: ChatOpenRequestCoordinator.Token,
    ): PreparedGroupChatOpen? {
        val selection =
            groupChatOpenCoordinator.prepare(
                chatId,
                chatName,
                communityId,
                expectedSelectionRevision,
                requestToken,
            ) ?: return null
        val seam = chatOpenTestSeam
        val stored = if (seam != null) seam.loadStored(communityId) else configStore.loadStored(communityId)
        val communityKey = stored?.let { loadRosterCommunityKey(it) }
        val kind =
            seam?.chatKind?.invoke(communityId, chatId)
                ?: runCatching { chatRegistry.all(communityId).firstOrNull { it.id == chatId }?.kind }
                    .getOrNull() ?: "group"
        return PreparedGroupChatOpen(selection, stored, communityKey, kind)
    }

    private fun installPreparedGroupChat(plan: PreparedGroupChatOpen): Boolean {
        val selection = plan.selection
        if (!chatOpenRequestCoordinator.isCurrent(selection.requestToken)) return false
        val installed =
            chatOpenRequestCoordinator.runIfCurrent(selection.requestToken) {
                EngineWiring.switchToChatIfCurrent(
                    guard = runtimeSelectionGuard,
                    expectedSelectionRevision = selection.selectionRevision,
                    expectedGraph = selection.graph,
                    chatId = selection.chatId,
                    chatName = selection.chatName,
                    chatKey = selection.chatKey,
                    roster = listOf(selection.graph.senderIdentifier),
                    memberNames = emptyMap(),
                    isCommunitySelected = { currentCommunityId == selection.communityId },
                    recipientReadiness = RecipientReadiness.Loading,
                )
            }
        if (!installed) return false
        val graph =
            runtimeGraph()?.takeIf {
                it.chatId == selection.chatId &&
                    it.communityId == selection.communityId &&
                    it.communityRuntimeKey == selection.graph.communityRuntimeKey &&
                    runtimeSelectionGuard.isCurrent(selection.selectionRevision)
            } ?: return false
        val stored = plan.stored
        if (stored == null) {
            updateRosterIfCurrent(
                selection.requestToken,
                selection.selectionRevision,
                graph,
                selection.graph.communityRuntimeKey,
                selection.communityId,
                selection.chatId,
            ) { graph.updateRecipientReadiness(RecipientReadiness.Unavailable(ROSTER_UNAVAILABLE)) }
        } else {
            val lookup =
                plan.communityKey?.let { key ->
                    rosterCache.lookup(selection.communityId, selection.chatId, key, selection.chatKey, graph.identity)
                }
            val cached = lookup?.roster
            val cacheGeneration = lookup?.generation ?: rosterCache.generation()
            if (cached != null) {
                updateRosterIfCurrent(
                    selection.requestToken,
                    selection.selectionRevision,
                    graph,
                    selection.graph.communityRuntimeKey,
                    selection.communityId,
                    selection.chatId,
                ) { applyRoster(graph, cached.entries) }
            }
            launchRosterRead(
                graph,
                selection.requestToken,
                selection.selectionRevision,
                stored,
                plan.communityKey,
                cacheGeneration,
                cached != null,
                plan.kind,
            )
        }
        return true
    }

    fun retryRecipientRoster(graph: RuntimeGraph) {
        if (runtimeGraph() !== graph || graph.recipientSnapshot() !is RecipientReadiness.Unavailable) return
        val seam = chatOpenTestSeam
        val stored = if (seam != null) seam.loadStored(graph.communityId) else configStore.loadStored(graph.communityId)
        if (stored == null) return
        val communityKey = loadRosterCommunityKey(stored) ?: return
        val requestToken = beginChatOpenRequest()
        val selectionRevision = runtimeSelectionGuard.current()
        val lookup = rosterCache.lookup(graph.communityId, graph.chatId, communityKey, graph.chatKey, graph.identity)
        val cached = lookup.roster
        val cacheGeneration = lookup.generation
        if (!updateRosterIfCurrent(
                requestToken,
                selectionRevision,
                graph,
                graph.communityRuntimeKey,
                graph.communityId,
                graph.chatId,
            ) {
                if (cached == null) {
                    graph.updateRecipientReadiness(RecipientReadiness.Loading)
                } else {
                    applyRoster(graph, cached.entries)
                }
            }
        ) {
            return
        }
        val kind =
            chatOpenTestSeam?.chatKind?.invoke(graph.communityId, graph.chatId)
                ?: runCatching { chatRegistry.all(graph.communityId).firstOrNull { it.id == graph.chatId }?.kind }
                    .getOrNull() ?: "group"
        launchRosterRead(graph, requestToken, selectionRevision, stored, communityKey, cacheGeneration, cached != null, kind)
    }

    private fun launchRosterRead(
        graph: RuntimeGraph,
        requestToken: ChatOpenRequestCoordinator.Token,
        selectionRevision: Long,
        stored: StoredConnection,
        communityKey: ChatKey?,
        cacheGeneration: Long,
        cachedReady: Boolean,
        chatKind: String,
    ) {
        val runtimeKey = graph.communityRuntimeKey
        val communityId = graph.communityId
        val chatId = graph.chatId
        val testSeam = chatOpenTestSeam
        val enrichment =
            RecipientRosterEnricher(
                scope = appScope,
                graph = graph,
                applyIfCurrent = { update ->
                    updateRosterIfCurrent(requestToken, selectionRevision, graph, runtimeKey, communityId, chatId, update)
                },
                read = {
                    val verified =
                        testSeam?.readDirectory(stored) ?: run {
                            val key = communityKey ?: throw IllegalStateException("Community key is unavailable")
                            val service =
                                directoryFactory.directoryService(
                                    baseUrl = stored.config.baseUrl, username = stored.config.username,
                                    appPassword = stored.config.appPassword, communityRoot = stored.config.chatRoot,
                                )
                            service.readDirectory(key)
                        }
                    if (chatKind == "dm" && !verified.listingFailed) {
                        verified.copy(
                            entries =
                                verified.entries.filter { entry ->
                                    val dmId =
                                        ChatIds.dmChatId(
                                            crypto.nativeCrypto(),
                                            graph.identity.copyBoxPublic(),
                                            entry.copyBoxPublicKey(),
                                        )
                                    dmId == chatId
                                },
                        )
                    } else {
                        verified
                    }
                },
                preserveReadyOnFailure = cachedReady,
                commitVerified = { result, isCurrent, apply ->
                    val key = communityKey ?: return@RecipientRosterEnricher false
                    val provenance = RosterCacheProvenance.digest(communityId, chatId, key, graph.chatKey, graph.identity)
                    val cached = CachedVerifiedRoster(communityId, chatId, provenance, result.entries)
                    rosterCache.commit(cacheGeneration, cached, isCurrent, apply)
                },
            ).start()
        testSeam?.onRosterEnrichmentCompleted?.let { callback -> enrichment.invokeOnCompletion { callback() } }
    }

    private fun loadRosterCommunityKey(stored: StoredConnection): ChatKey? {
        val seam = chatOpenTestSeam
        return if (seam != null) seam.loadChatKey(stored.chatId) else crypto.chatKeyStore(requireContext()).load(stored.chatId)
    }

    private fun applyRoster(
        graph: RuntimeGraph,
        entries: List<DirectoryEntry>,
    ) {
        graph.memberNames = entries.associate { Hex.encode(it.copySigningPublicKey()) to it.displayName }
        graph.setMemberNamesError(null)
        graph.updateRecipientReadiness(RecipientReadiness.Ready(entries.map { Hex.encode(it.copySigningPublicKey()) }))
    }

    private fun isRosterContextCurrent(
        requestToken: ChatOpenRequestCoordinator.Token,
        selectionRevision: Long,
        graph: RuntimeGraph,
        expectedRuntimeKey: String,
        expectedCommunityId: String,
        expectedChatId: String,
    ): Boolean =
        chatOpenRequestCoordinator.isCurrent(requestToken) &&
            runtimeSelectionGuard.isCurrent(selectionRevision) &&
            EngineWiring.isGraphCurrent(graph) {
                graph.communityRuntimeKey == expectedRuntimeKey &&
                    graph.communityId == expectedCommunityId &&
                    graph.chatId == expectedChatId &&
                    currentCommunityId == expectedCommunityId
            }

    private fun updateRosterIfCurrent(
        requestToken: ChatOpenRequestCoordinator.Token,
        selectionRevision: Long,
        graph: RuntimeGraph,
        expectedRuntimeKey: String,
        expectedCommunityId: String,
        expectedChatId: String,
        update: () -> Unit,
    ): Boolean {
        return chatOpenRequestCoordinator.runIfCurrent(requestToken) {
            runtimeSelectionGuard.runIfCurrent(selectionRevision) {
                EngineWiring.updateGraphIfCurrent(
                    expectedGraph = graph,
                    isContextCurrent = {
                        graph.communityRuntimeKey == expectedRuntimeKey &&
                            graph.communityId == expectedCommunityId &&
                            graph.chatId == expectedChatId &&
                            currentCommunityId == expectedCommunityId
                    },
                    update = update,
                )
            }
        }
    }

    /** All chats registered under [communityId]. */
    fun chatsForCommunity(communityId: String): List<ChatRegistry.Entry> = chatRegistry.all(communityId)

    /** A unified view of all chats across all communities — for the unified chat list. */
    data class UnifiedChat(
        val chatId: String,
        val name: String,
        // "general", "group", "dm"
        val kind: String,
        val communityId: String,
        val communityName: String,
    )

    /**
     * Read the on-disk chat-directory for all communities and auto-add any newly discovered public
     * group chats to the local [ChatRegistry]. Best-effort — directory read failures are silent;
     * the next poll cycle retries.
     */
    suspend fun discoverPublicChats() {
        for (community in communityRegistry.all()) {
            val stored = configStore.loadStored(community.id) ?: continue
            val communityKey = crypto.chatKeyStore(requireContext()).load(stored.chatId) ?: continue
            try {
                val service =
                    chatDirectoryFactory.chatDirectoryService(
                        baseUrl = stored.config.baseUrl,
                        username = stored.config.username,
                        appPassword = stored.config.appPassword,
                        communityRoot = stored.config.chatRoot,
                    )
                val result = service.readChatDirectory(communityKey)
                for (entry in result.entries) {
                    if (entry.access != ChatAccess.PUBLIC) continue
                    val chatIdHex = Hex.encode(entry.chatId)
                    // Public groups use the community key; restore a missing key for existing rows too.
                    val keyStore = crypto.chatKeyStore(requireContext())
                    if (keyStore.load(chatIdHex) == null) keyStore.store(chatIdHex, communityKey)
                    val existing = chatRegistry.all(community.id)
                    if (existing.none { it.id == chatIdHex }) {
                        chatRegistry.add(
                            community.id,
                            ChatRegistry.Entry(chatIdHex, entry.title, "group"),
                        )
                    }
                }
            } catch (_: Exception) {
                // best-effort — retry next cycle
            }
        }
    }

    /** All chats across all communities, flattened for the unified chat list. */
    fun allChats(): List<UnifiedChat> {
        val result = mutableListOf<UnifiedChat>()
        for (community in communityRegistry.all()) {
            result.add(UnifiedChat(community.chatId, "General", "general", community.id, community.name))
            for (chat in chatRegistry.all(community.id)) {
                if (chat.kind != "general") {
                    result.add(UnifiedChat(chat.id, chat.name, chat.kind, community.id, community.name))
                }
            }
        }
        return result
    }

    /** The first existing connection config from a community the user hosts, or `null`. Used by the
     *  create-community flow to offer server setting inheritance. */
    fun existingConnectionConfig(): ConnectionConfig? {
        for (community in communityRegistry.all()) {
            val stored = configStore.loadStored(community.chatId) ?: continue
            return stored.config
        }
        return configStore.loadStored()?.config
    }

    /** All joined communities from the registry. */
    fun communities(): List<CommunityRegistry.Entry> = communityRegistry.all()

    /** Observable unread count for a community/chat pair, including chats outside the active graph. */
    fun observeUnreadCount(
        communityId: String,
        chatId: String,
    ): Flow<Int> {
        val graph = runtimeGraph()
        val store =
            if (graph?.communityId == communityId) {
                graph.store
            } else {
                val context = appContext ?: return flowOf(0)
                val db = MessengerDatabase.get(context)
                MessageStore(db.messageDao(), db.syncCursorDao(), communityId)
            }
        return store.observeUnreadCount(chatId)
    }

    /** Switch the active community to [communityId] — rebuilds the engine for that community. */
    fun beginChatOpenRequest(): ChatOpenRequestCoordinator.Token =
        chatOpenRequestCoordinator.begin {
            val active = runtimeGraph()
            if (active?.recipientSnapshot() is RecipientReadiness.Loading) {
                EngineWiring.updateGraphIfCurrent(
                    active,
                    isContextCurrent = { runtimeGraph() === active },
                    update = { active.updateRecipientReadiness(RecipientReadiness.Unavailable(ROSTER_UNAVAILABLE)) },
                )
            }
        }

    suspend fun switchToCommunity(
        communityId: String,
        requestToken: ChatOpenRequestCoordinator.Token = beginChatOpenRequest(),
    ): Boolean {
        if (!chatOpenRequestCoordinator.isCurrent(requestToken)) return false
        val expectedRuntimeKey = runtimeGraph()?.communityRuntimeKey
        return AccountMutationBarrier.process.withStableAccount {
            if (expectedRuntimeKey != null && runtimeGraph()?.communityRuntimeKey != expectedRuntimeKey) {
                false
            } else {
                switchToCommunityExclusive(communityId, requestToken)
            }
        }
    }

    private fun switchToCommunityExclusive(
        communityId: String,
        requestToken: ChatOpenRequestCoordinator.Token,
        refreshGeneralRoster: Boolean = true,
    ): Boolean {
        val rotationSeam = credentialRotationTestSeam
        val stored =
            if (rotationSeam != null) {
                rotationSeam.loadStored(communityId)
            } else {
                configStore.loadStored(communityId)
            } ?: return false
        val chatKey =
            if (rotationSeam != null) {
                rotationSeam.loadChatKey(stored.chatId)
            } else {
                crypto.chatKeyStore(requireContext()).load(stored.chatId)
            } ?: return false
        val identity =
            if (rotationSeam != null) {
                runtimeGraph()?.identity ?: return false
            } else {
                runBlocking { identityFactory.identityStore(requireContext()).loadOrCreate() }
            }
        val switched =
            chatOpenRequestCoordinator.runIfCurrentSerialized(requestToken) {
                runtimeSelectionGuard.begin()
                currentCommunityId = communityId
                if (rotationSeam != null) {
                    rotationSeam.onCommunitySelected(communityId)
                } else {
                    UserSettings.selectCommunity(communityId)
                    activeCommunityStore.select(communityId)
                }
                EngineWiring.reconfigure(
                    config = stored.config,
                    chatId = stored.chatId,
                    communityName = stored.communityName,
                    chatKey = chatKey,
                    identity = identity,
                    communityId = communityId,
                    roster = listOf(Hex.encode(identity.copySignPublic())),
                    recipientReadiness = RecipientReadiness.Loading,
                )
                true
            }
        if (!switched) return false
        if (refreshGeneralRoster && rotationSeam == null) runtimeGraph()?.let(::prepareGeneralRoster)
        return true
    }

    /**
     * Start a DM with [peer] in the current community. Derives the deterministic DM chat-id from the
     * two box public keys, provisions the per-pair DH key, registers the chat, and switches to it.
     * Returns the DM chat-id on success, or `null` if the graph is absent / provision fails.
     */
    suspend fun startDm(peer: DirectoryEntry): String? {
        val expectedRuntimeKey = runtimeGraph()?.communityRuntimeKey ?: return null
        return AccountMutationBarrier.process.withStableAccount {
            if (runtimeGraph()?.communityRuntimeKey != expectedRuntimeKey) null else startDmExclusive(peer)
        }
    }

    private fun startDmExclusive(peer: DirectoryEntry): String? {
        val graph = runtimeGraph() ?: return null
        val identity = graph.identity
        val myBoxPub = identity.copyBoxPublic()
        val peerBoxPub = peer.copyBoxPublicKey()
        val chatId = ChatIds.dmChatId(crypto.nativeCrypto(), myBoxPub, peerBoxPub)

        // Re-provisioning may replace a chat key, so invalidate before the key-store mutation.
        rosterCache.invalidate(graph.communityId, chatId)
        val provisioner =
            RemoteChatProvisioner(
                identityCrypto = identityFactory.identityCrypto(),
                chatKeyStore = crypto.chatKeyStore(requireContext()),
            )
        when (provisioner.provision(identity, peer, chatId)) {
            is org.openwebdav.messenger.directory.ProvisionOutcome.Failed -> return null
            is org.openwebdav.messenger.directory.ProvisionOutcome.Provisioned -> { /* ok */ }
        }

        // Register the DM chat in the registry for this community.
        chatRegistry.add(graph.communityId, ChatRegistry.Entry(chatId, peer.displayName, "dm"))

        // DM roster: just the two participants (self + peer). The peer's on-disk identifier
        // is the hex of their Ed25519 signing public key.
        val peerId = Hex.encode(peer.copySigningPublicKey())

        // Switch the active send path to the DM chat.
        if (!switchToChat(graph, chatId, peer.displayName, peerId)) return null
        val dmGraph = runtimeGraph() ?: return null
        val stored = configStore.loadStored(graph.communityId) ?: return chatId
        val communityKey = loadRosterCommunityKey(stored) ?: return chatId
        val own = dmGraph.identity.publicIdentity()
        val verifiedEntries =
            listOf(
                DirectoryEntry(UserSettings.displayName, own.copySignPub(), own.copyBoxPub()),
                peer,
            )
        val provenance =
            RosterCacheProvenance.digest(
                graph.communityId,
                chatId,
                communityKey,
                dmGraph.chatKey,
                dmGraph.identity,
            )
        rosterCache.commit(
            rosterCache.generation(),
            CachedVerifiedRoster(graph.communityId, chatId, provenance, verifiedEntries),
            isCurrent = { runtimeGraph() === dmGraph },
        ) { runtimeGraph() === dmGraph }
        return chatId
    }

    /**
     * Switch the active send-path chat within the current community. Loads the per-chat key from the
     * Keystore and builds a new [RuntimeGraph] with the DM roster = [self, peerId].
     */
    private fun switchToChat(
        expectedGraph: RuntimeGraph,
        chatId: String,
        chatName: String,
        peerId: String,
    ): Boolean {
        val chatKey = crypto.chatKeyStore(requireContext()).load(chatId) ?: return false
        val roster = listOf(expectedGraph.senderIdentifier, peerId)
        val memberNames = mapOf(peerId to chatName)
        val revision = runtimeSelectionGuard.begin()
        return EngineWiring.switchToChatIfCurrent(
            runtimeSelectionGuard,
            revision,
            expectedGraph,
            chatId,
            chatName,
            chatKey,
            roster,
            memberNames,
            isCommunitySelected = { currentCommunityId == expectedGraph.communityId },
        )
    }

    /**
     * Load the verified directory entries (members) for [communityId]. Returns the list of members,
     * or empty if the community is not found or the directory read fails.
     */
    suspend fun loadMembers(communityId: String): List<DirectoryEntry> {
        val stored = configStore.loadStored(communityId)
        if (stored == null) {
            android.util.Log.w("AppContainer", "loadMembers: no stored config for $communityId")
            return emptyList()
        }
        android.util.Log.d("AppContainer", "loadMembers: loaded config, chatId=${stored.chatId}")
        val chatKey = crypto.chatKeyStore(requireContext()).load(stored.chatId)
        if (chatKey == null) {
            android.util.Log.w("AppContainer", "loadMembers: no chat key for ${stored.chatId}")
            return emptyList()
        }
        android.util.Log.d("AppContainer", "loadMembers: loaded chat key, reading directory...")
        val service =
            directoryFactory.directoryService(
                baseUrl = stored.config.baseUrl,
                username = stored.config.username,
                appPassword = stored.config.appPassword,
                communityRoot = stored.config.chatRoot,
            )
        return try {
            val dir = service.readDirectory(chatKey)
            android.util.Log.d("AppContainer", "loadMembers: read ${dir.entries.size} entries")
            dir.entries
        } catch (e: Exception) {
            android.util.Log.e("AppContainer", "loadMembers: directory read failed", e)
            emptyList()
        }
    }

    /** The live engine graph for the joined chat, or `null` if nothing is joined yet. */
    fun runtimeGraph(): RuntimeGraph? = EngineWiring.current()

    /**
     * Re-apply the current poll interval (WorkManager or foreground service).
     * Call from settings after changing pollIntervalSeconds.
     */
    fun reschedulePoll() {
        val ctx = appContext ?: return
        val memberPref = UserSettings.pollIntervalSeconds.toLong()
        val communityFloor = UserSettings.communityMinPollSeconds.toLong()
        // Effective interval for foreground service: user + community floor, no platform clamp.
        val rawEffective = maxOf(memberPref, communityFloor)
        val workManagerFloorSeconds = PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS / 1000
        val wm = WorkManager.getInstance(ctx)
        if (rawEffective < workManagerFloorSeconds) {
            FastPollManager.enable(ctx, wm, rawEffective)
        } else {
            FastPollManager.disable(ctx, wm)
            // WorkManager path: apply the 60s platform floor (which gets clamped to 900s anyway).
            SyncScheduler.schedule(wm, SyncScheduler.effectiveIntervalSeconds(memberPref, communityFloor.toInt()))
        }
    }

    /**
     * Load member names from the on-disk directories of all joined communities.
     * Returns a map of signing-pubkey-hex → displayName. Suspending — call from a coroutine.
     */
    suspend fun loadMemberNames(): Map<String, String> {
        val communities = communityRegistry.all()
        android.util.Log.d("AppContainer", "loadMemberNames: ${communities.size} communities registered")
        for (community in communities) {
            android.util.Log.d("AppContainer", "loadMemberNames: trying chatId=${community.chatId}")
            val entries = loadMembers(community.chatId)
            android.util.Log.d("AppContainer", "loadMemberNames: got ${entries.size} entries for chatId=${community.chatId}")
            if (entries.isNotEmpty()) {
                val names = entries.associate { Hex.encode(it.copySigningPublicKey()) to it.displayName }
                android.util.Log.d("AppContainer", "loadMemberNames: built ${names.size} name mappings: $names")
                return names
            }
        }
        android.util.Log.w("AppContainer", "loadMemberNames: all communities returned empty entries")
        return emptyMap()
    }

    internal fun prepareGeneralRoster(graph: RuntimeGraph) {
        val requestToken = chatOpenRequestCoordinator.begin()
        val seam = chatOpenTestSeam
        val stored = if (seam != null) seam.loadStored(graph.communityId) else configStore.loadStored(graph.communityId)
        stored ?: return
        val communityKey = loadRosterCommunityKey(stored)
        val revision = runtimeSelectionGuard.current()
        val lookup = communityKey?.let { rosterCache.lookup(graph.communityId, graph.chatId, it, graph.chatKey, graph.identity) }
        val cached = lookup?.roster
        val generation = lookup?.generation ?: rosterCache.generation()
        val isCurrent = {
            isRosterContextCurrent(requestToken, revision, graph, graph.communityRuntimeKey, graph.communityId, graph.chatId)
        }
        if (!updateRosterIfCurrent(
                requestToken,
                revision,
                graph,
                graph.communityRuntimeKey,
                graph.communityId,
                graph.chatId,
            ) {
                if (cached == null) graph.updateRecipientReadiness(RecipientReadiness.Loading) else applyRoster(graph, cached.entries)
            }
        ) {
            return
        }
        if (communityKey == null) {
            if (cached == null) {
                updateRosterIfCurrent(
                    requestToken,
                    revision,
                    graph,
                    graph.communityRuntimeKey,
                    graph.communityId,
                    graph.chatId,
                ) { graph.updateRecipientReadiness(RecipientReadiness.Unavailable(ROSTER_UNAVAILABLE)) }
            }
            return
        }
        val refresh =
            appScope.launch {
                try {
                    val result =
                        if (seam != null) {
                            seam.readDirectory(stored)
                        } else {
                            val service =
                                directoryFactory.directoryService(
                                    baseUrl = stored.config.baseUrl,
                                    username = stored.config.username,
                                    appPassword = stored.config.appPassword,
                                    communityRoot = stored.config.chatRoot,
                                )
                            service.readDirectory(communityKey)
                        }
                    if (!result.listingFailed) {
                        val provenance =
                            RosterCacheProvenance.digest(
                                graph.communityId,
                                graph.chatId,
                                communityKey,
                                graph.chatKey,
                                graph.identity,
                            )
                        val roster = CachedVerifiedRoster(graph.communityId, graph.chatId, provenance, result.entries)
                        rosterCache.commit(generation, roster, isCurrent) {
                            updateRosterIfCurrent(
                                requestToken,
                                revision,
                                graph,
                                graph.communityRuntimeKey,
                                graph.communityId,
                                graph.chatId,
                            ) { applyRoster(graph, result.entries) }
                        }
                    } else if (cached == null) {
                        updateRosterIfCurrent(
                            requestToken,
                            revision,
                            graph,
                            graph.communityRuntimeKey,
                            graph.communityId,
                            graph.chatId,
                        ) { graph.updateRecipientReadiness(RecipientReadiness.Unavailable(ROSTER_UNAVAILABLE)) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (cached == null) {
                        updateRosterIfCurrent(
                            requestToken,
                            revision,
                            graph,
                            graph.communityRuntimeKey,
                            graph.communityId,
                            graph.chatId,
                        ) { graph.updateRecipientReadiness(RecipientReadiness.Unavailable(ROSTER_UNAVAILABLE)) }
                    }
                }
            }
        seam?.onGeneralRosterCompleted?.let { callback -> refresh.invokeOnCompletion { callback() } }
    }

    /**
     * Write community metadata (poll floor + retention window) to `meta/community.json` on the WebDAV
     * disk, signed by the host identity. Writes are serialized per community; a superseded request
     * cannot become the final remote or cached policy.
     */
    suspend fun updateCommunityRetention(days: Int): CommunityMetadataUpdate = updateCommunityPolicy(days, null)

    suspend fun updateCommunityPollFloor(seconds: Int): CommunityMetadataUpdate = updateCommunityPolicy(null, seconds)

    private suspend fun updateCommunityPolicy(
        retentionDays: Int?,
        pollSeconds: Int?,
    ): CommunityMetadataUpdate {
        val graph = runtimeGraph() ?: return CommunityMetadataUpdate.Failed("Community runtime is unavailable")
        val id = graph.communityId
        val committed = CommunityPolicy(UserSettings.retentionDaysFor(id), UserSettings.pollFloorFor(id))
        val request =
            if (retentionDays != null) {
                communityPolicyCoordinator.retention(id, committed, retentionDays)
            } else {
                communityPolicyCoordinator.pollFloor(id, committed, checkNotNull(pollSeconds))
            }
        return AccountMutationBarrier.process.withExclusive {
            if (!communityPolicyCoordinator.isLatest(request)) return@withExclusive CommunityMetadataUpdate.Superseded
            if (runtimeGraph() !== graph) {
                communityPolicyCoordinator.complete(request)
                return@withExclusive CommunityMetadataUpdate.Superseded
            }
            val result =
                try {
                    CommunityMetadata.write(
                        TransportFactory.create(graph.config),
                        CommunityMetadata(request.policy.pollFloorSeconds, request.policy.retentionDays),
                        graph.identity,
                        identityFactory.identityCrypto(),
                    )
                } catch (failure: Exception) {
                    communityPolicyCoordinator.complete(request)
                    return@withExclusive CommunityMetadataUpdate.Failed(failure.message ?: "Unknown write failure")
                }
            if (result !is WebDavResult.Success) {
                communityPolicyCoordinator.complete(request)
                CommunityMetadataUpdate.Rejected(result)
            } else if (communityPolicyCoordinator.isLatest(request)) {
                UserSettings.setCommunityMetadata(id, request.policy.pollFloorSeconds, request.policy.retentionDays)
                communityPolicyCoordinator.complete(request)
                CommunityMetadataUpdate.Saved
            } else {
                CommunityMetadataUpdate.Superseded
            }
        }
    }

    /** Whether the current user is the host of the active community. */
    val isHost: Boolean get() = UserSettings.isHostFor(currentCommunityId)

    /**
     * Rotate the WebDAV credential for all members EXCEPT [excludeMemberSignPub]. The host provides a new
     * [newUrl], [newUsername], and [newPassword]; the method reads the current verified member list from
     * the directory, seals the new config for each member (except the excluded one) via
     * [CredentialRotation.sealForMember], writes each blob to `meta/credentials/<memberSignPubHex>` on
     * the WebDAV disk, and updates the local [ConnectionConfig] via [configStore].
     *
     * Returns `true` on success (all blobs written + local config updated), `false` on any failure.
     * Best-effort: a partial write (some members written, some failed) is still reported as failure;
     * the host re-runs to retry.
     */
    suspend fun rotateCredential(
        newUrl: String,
        newUsername: String,
        newPassword: String,
        excludeMemberSignPub: String,
    ): Boolean {
        val ownerCommunityId =
            AccountMutationBarrier.process.withStableAccount {
                runtimeGraph()?.communityId?.takeIf { it == currentCommunityId }
            } ?: return false
        return AccountMutationBarrier.process.withCommunityCredentialRotation(ownerCommunityId) {
            rotateCredentialForOwner(ownerCommunityId, newUrl, newUsername, newPassword, excludeMemberSignPub)
        }
    }

    private suspend fun rotateCredentialForOwner(
        ownerCommunityId: String,
        newUrl: String,
        newUsername: String,
        newPassword: String,
        excludeMemberSignPub: String,
    ): Boolean {
        val snapshot =
            AccountMutationBarrier.process.withStableAccount {
                val graph = runtimeGraph() ?: return@withStableAccount null
                if (graph.communityId != ownerCommunityId || currentCommunityId != ownerCommunityId) {
                    return@withStableAccount null
                }
                val seam = credentialRotationTestSeam
                val stored =
                    if (seam != null) {
                        seam.loadStored(ownerCommunityId)
                    } else {
                        configStore.loadStored(ownerCommunityId)
                    } ?: return@withStableAccount null
                if (stored.config != graph.config) return@withStableAccount null
                val communityKey =
                    if (seam != null) {
                        seam.loadChatKey(stored.chatId)
                    } else {
                        crypto.chatKeyStore(requireContext()).load(stored.chatId)
                    } ?: return@withStableAccount null
                CredentialRotationSnapshot(
                    ownerCommunityId = ownerCommunityId,
                    graph = graph,
                    stored = stored,
                    communityKey = communityKey,
                    replacementGeneration = AccountMutationBarrier.process.replacementGeneration(),
                    testSeam = seam,
                )
            } ?: return false

        val seam = snapshot.testSeam
        val members =
            try {
                if (seam != null) {
                    seam.readDirectory(snapshot.stored, snapshot.communityKey)
                } else {
                    directoryFactory
                        .directoryService(
                            baseUrl = snapshot.stored.config.baseUrl,
                            username = snapshot.stored.config.username,
                            appPassword = snapshot.stored.config.appPassword,
                            communityRoot = snapshot.stored.config.chatRoot,
                        ).readDirectory(snapshot.communityKey).entries
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return false
            }

        val newConfig =
            ConnectionConfig(
                baseUrl = newUrl,
                username = newUsername,
                appPassword = newPassword,
                chatRoot = snapshot.stored.config.chatRoot,
            )
        val idCrypto = identityFactory.identityCrypto()
        val transport = if (seam == null) TransportFactory.create(snapshot.stored.config) else null
        transport?.ensureCollection("meta/credentials")

        var allOk = true
        for (member in members) {
            val memberHex = Hex.encode(member.copySigningPublicKey())
            if (memberHex == excludeMemberSignPub) continue
            try {
                val blob =
                    CredentialRotation.sealForMember(
                        config = newConfig,
                        memberBoxPublicKey = member.copyBoxPublicKey(),
                        identityCrypto = idCrypto,
                        hostIdentity = snapshot.graph.identity,
                    )
                val path = "meta/credentials/$memberHex"
                val written =
                    if (seam != null) {
                        seam.writeCredential(snapshot.ownerCommunityId, snapshot.stored.config, path, blob)
                    } else {
                        transport!!.write(path, blob) is WebDavResult.Success
                    }
                if (!written) allOk = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                allOk = false
            }
        }
        if (!allOk) return false

        return AccountMutationBarrier.process.withStableAccount {
            if (
                snapshot.replacementGeneration != AccountMutationBarrier.process.replacementGeneration()
            ) {
                return@withStableAccount false
            }
            val currentStored =
                loadRotationStored(snapshot.ownerCommunityId, seam) ?: return@withStableAccount false
            if (currentStored != snapshot.stored) return@withStableAccount false
            val currentKey =
                loadRotationChatKey(snapshot.stored.chatId, seam) ?: return@withStableAccount false
            val currentKeyBytes = currentKey.export()
            val capturedKeyBytes = snapshot.communityKey.export()
            val keyStillCurrent =
                try {
                    currentKeyBytes.contentEquals(capturedKeyBytes)
                } finally {
                    currentKeyBytes.fill(0)
                    capturedKeyBytes.fill(0)
                }
            if (!keyStillCurrent) return@withStableAccount false

            // A community switch during WebDAV I/O may commit only to the captured owner, never reinstall over B.
            val ownerStillSelected = currentCommunityId == snapshot.ownerCommunityId
            val activeGraph = runtimeGraph()
            if (
                ownerStillSelected &&
                (
                    activeGraph?.communityId != snapshot.ownerCommunityId ||
                        activeGraph.communityRuntimeKey != snapshot.graph.communityRuntimeKey
                )
            ) {
                return@withStableAccount false
            }

            val rotated = snapshot.stored.copy(config = newConfig)
            val saved =
                if (seam != null) {
                    seam.saveStored(snapshot.ownerCommunityId, rotated)
                } else {
                    configStore.save(
                        rotated.config,
                        rotated.chatId,
                        rotated.communityName,
                        communityId = snapshot.ownerCommunityId,
                    )
                    true
                }
            if (!saved) return@withStableAccount false
            if (
                ownerStillSelected &&
                !EngineWiring.reconfigureIfCurrent(
                    expectedCommunityRuntimeKey = snapshot.graph.communityRuntimeKey,
                    config = newConfig,
                    communityId = snapshot.ownerCommunityId,
                )
            ) {
                return@withStableAccount false
            }
            true
        }
    }

    private fun loadRotationStored(
        communityId: String,
        seam: CredentialRotationTestSeam?,
    ): StoredConnection? = if (seam != null) seam.loadStored(communityId) else configStore.loadStored(communityId)

    private fun loadRotationChatKey(
        chatId: String,
        seam: CredentialRotationTestSeam?,
    ): ChatKey? = if (seam != null) seam.loadChatKey(chatId) else crypto.chatKeyStore(requireContext()).load(chatId)

    /**
     * Build the `owdm1:` invite for the [graph]'s joined chat (the owner shares it). Role-agnostic at the
     * code level (any holder of the config + key can mint one — plan: "let any member invite" is later just
     * a UI toggle). Off the UI thread (the codec gzips/base64s on its own dispatcher). The raw key bytes
     * are wiped after framing — they live in the returned string only (a bearer token, never logged).
     */
    suspend fun buildInvite(graph: RuntimeGraph): String {
        val raw = graph.chatKey.export()
        return try {
            InviteCodec().encode(
                InviteToken(
                    baseUrl = graph.config.baseUrl,
                    username = graph.config.username,
                    appPassword = graph.config.appPassword,
                    chatRoot = graph.config.chatRoot,
                    chatId = graph.chatId,
                    chatKey = raw,
                    communityName = graph.communityName,
                ),
            )
        } finally {
            raw.fill(0)
        }
    }

    private fun requireContext(): Context = appContext ?: error("AppContainer.bind(context) not called")

    /** Production [OnboardingService.Deps] — native crypto + Keystore-wrapped stores + the engine wiring. */
    private fun productionOnboardingDeps(): OnboardingService.Deps =
        object : OnboardingService.Deps {
            override fun keySources(): KeySources = crypto.keySources()

            override fun chatKeyStore(): ChatKeyStorePort = crypto.chatKeyStore(requireContext())

            override fun saveConfig(
                config: ConnectionConfig,
                chatId: String,
                communityName: String,
            ) {
                // Use chatId as communityId for this namespace and select it across process restarts.
                configStore.save(config, chatId, communityName, communityId = chatId)
                runtimeSelectionGuard.begin()
                currentCommunityId = chatId
                activeCommunityStore.select(chatId)
                UserSettings.selectCommunity(chatId)
                communityRegistry.add(CommunityRegistry.Entry(chatId, communityName, chatId))
                // Auto-create the "General" chat for the new community.
                chatRegistry.add(chatId, ChatRegistry.Entry(chatId, "General", "general"))
            }

            override suspend fun ensureIdentity(): Identity = identityFactory.identityStore(requireContext()).loadOrCreate()

            override fun newChatId(): String = randomChatId()

            override fun reconfigure(
                config: ConnectionConfig,
                chatId: String,
                communityName: String,
                chatKey: ChatKey,
                identity: Identity,
                isHost: Boolean,
            ) {
                UserSettings.setHostFor(chatId, isHost)
                EngineWiring.reconfigure(
                    config,
                    chatId,
                    communityName,
                    chatKey,
                    identity,
                    communityId = chatId,
                    roster = listOf(Hex.encode(identity.copySignPublic())),
                    recipientReadiness = RecipientReadiness.Loading,
                )
                runtimeGraph()?.takeIf { it.communityId == chatId }?.let(::prepareGeneralRoster)
                val runtimeGeneration = runtimeGraph()?.takeIf { it.communityId == chatId }?.communityRuntimeKey ?: return
                val initialPolicy = if (isHost) communityPolicyCoordinator.defaults(chatId) else null
                appScope.launch {
                    try {
                        val transport = TransportFactory.create(config)
                        communityPolicyCoordinator.runInitialWrites(
                            request = initialPolicy,
                            expectedRuntimeGeneration = runtimeGeneration,
                            currentRuntimeGeneration = { runtimeGraph()?.communityRuntimeKey },
                            writeRoster = {
                                RosterService(transport).addMyself(
                                    org.openwebdav.messenger.protocol.Hex.encode(identity.copySignPublic()),
                                )
                            },
                            writePolicy = { policy ->
                                CommunityMetadata.write(
                                    transport = transport,
                                    metadata = CommunityMetadata(policy.pollFloorSeconds, policy.retentionDays),
                                    hostIdentity = identity,
                                    identityCrypto = identityFactory.identityCrypto(),
                                ) is WebDavResult.Success
                            },
                            commitPolicy = { policy ->
                                UserSettings.setCommunityMetadata(chatId, policy.pollFloorSeconds, policy.retentionDays)
                            },
                        )
                    } catch (_: Exception) {
                        initialPolicy?.let(communityPolicyCoordinator::complete)
                    }
                }
            }

            override suspend fun checkFolder(
                config: ConnectionConfig,
                root: String,
            ): OnboardingService.FolderCheck {
                val transport = TransportFactory.create(config)
                return when (val result = transport.list("")) {
                    is WebDavResult.Success ->
                        if (result.value.isEmpty()) {
                            OnboardingService.FolderCheck.Ok
                        } else {
                            OnboardingService.FolderCheck.Occupied
                        }
                    is WebDavResult.TransportError ->
                        if (result.code == 404) {
                            // Folder doesn't exist — ensure parent dirs, then create the leaf.
                            ensureParentFolders(config, root)
                            if (transport.ensureCollection("") is WebDavResult.Success) {
                                OnboardingService.FolderCheck.Ok
                            } else {
                                OnboardingService.FolderCheck.Error("cannot create folder '$root'")
                            }
                        } else {
                            OnboardingService.FolderCheck.Error(result.message ?: "cannot access folder")
                        }
                    else -> OnboardingService.FolderCheck.Error("cannot check folder")
                }
            }
        }

    /** Ensure every parent folder in [root] exists, from outermost to leaf-parent. */
    private suspend fun ensureParentFolders(
        config: ConnectionConfig,
        root: String,
    ) {
        val segments = root.split("/")
        if (segments.size <= 1) return
        // Build each prefix and ensure it as a collection.
        for (i in 1 until segments.size) {
            val parentPath = segments.take(i).joinToString("/")
            val parentConfig = config.copy(chatRoot = parentPath)
            TransportFactory.create(parentConfig).ensureCollection("")
        }
    }

    /**
     * A fresh opaque chat-id: 16 CSPRNG bytes Base32-lowercase-encoded (26 chars). The chat-id is not
     * secret — it names the chat on the disk — so a random token is enough to avoid collisions across
     * communities; the random chat KEY (separate, Keystore-wrapped) is what protects content.
     */
    private fun randomChatId(): String {
        val bytes = ByteArray(CHAT_ID_RANDOM_BYTES)
        SecureRandom().nextBytes(bytes)
        return Base32.encodeBase32Lower(bytes).take(CHAT_ID_CHARS)
    }

    private const val ROSTER_UNAVAILABLE = "Verified members unavailable — reconnect and retry"
    private const val CHAT_ID_RANDOM_BYTES = 16
    private const val CHAT_ID_CHARS = 26
}
