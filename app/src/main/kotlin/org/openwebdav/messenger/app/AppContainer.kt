package org.openwebdav.messenger.app

import android.content.Context
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.openwebdav.messenger.chatdirectory.ChatAccess
import org.openwebdav.messenger.chatdirectory.ChatDirectoryFactory
import org.openwebdav.messenger.chatdirectory.ChatKind
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.CryptoFactory
import org.openwebdav.messenger.crypto.KeySources
import org.openwebdav.messenger.directory.CredentialRotation
import org.openwebdav.messenger.directory.DirectoryEntry
import org.openwebdav.messenger.directory.DirectoryFactory
import org.openwebdav.messenger.directory.RemoteChatProvisioner
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.identity.IdentityFactory
import org.openwebdav.messenger.invite.InviteCodec
import org.openwebdav.messenger.invite.InviteToken
import org.openwebdav.messenger.keystore.ChatKeyStorePort
import org.openwebdav.messenger.keystore.ChatRegistry
import org.openwebdav.messenger.keystore.CommunityRegistry
import org.openwebdav.messenger.keystore.ConnectionConfigStore
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
    private val communityRegistry by lazy { CommunityRegistry(requireContext()) }
    private val chatRegistry by lazy { ChatRegistry(requireContext()) }
    private val directoryFactory by lazy { DirectoryFactory() }
    private val chatDirectoryFactory by lazy { ChatDirectoryFactory() }
    private val warmStarted = AtomicBoolean(false)

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Process-start readiness — `false` until [warmStart] has resolved the start graph. The UI collects
     * this to show a brief loading state instead of racing the async warm-start (start-destination race fix).
     */
    val ready: StateFlow<Boolean> get() = EngineWiring.ready

    /** The current community ID (for multi-chat enumeration). */
    val activeCommunityId: String get() = currentCommunityId

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
            EngineWiring.initialize(
                AndroidDeps(requireContext(), crypto, identityFactory, configStore, chatRegistry, directoryFactory),
            )
            refreshMemberNames()
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
    ): String? {
        val graph = runtimeGraph() ?: return null
        val stored = configStore.loadStored(communityId) ?: return null
        val keySources = crypto.keySources()
        // Public chats use the community key; private chats get a fresh random key.
        val chatKey =
            if (access == ChatAccess.PUBLIC) {
                crypto.chatKeyStore(requireContext()).load(stored.chatId) ?: return null
            } else {
                keySources.newRandomKey()
            }
        // Domain-separate group chat ids: BLAKE2b("owdm/group-chat/v1" ‖ 0x1F ‖ randomBytes ‖ communityId)
        val ctx = "owdm/group-chat/v1".toByteArray(Charsets.UTF_8)
        val input = ctx + byteArrayOf(0x1F) + chatKey.copyBytes() + communityId.toByteArray(Charsets.UTF_8)
        val hash = crypto.nativeCrypto().genericHash(input, 16)
        val chatId = Hex.encode(hash)
        crypto.chatKeyStore(requireContext()).store(chatId, chatKey)
        chatRegistry.add(communityId, ChatRegistry.Entry(chatId, name, "group"))

        // Publish public chats to the on-disk chat-directory so other members discover them.
        if (access == ChatAccess.PUBLIC) {
            try {
                val service =
                    chatDirectoryFactory.chatDirectoryService(
                        baseUrl = stored.config.baseUrl,
                        username = stored.config.username,
                        appPassword = stored.config.appPassword,
                        communityRoot = stored.config.chatRoot,
                    )
                val communityKey = crypto.chatKeyStore(requireContext()).load(stored.chatId) ?: return null
                service.publishChatEntry(
                    identity = graph.identity,
                    chatId = chatId.toByteArray(Charsets.UTF_8),
                    kind = ChatKind.GROUP,
                    access = ChatAccess.PUBLIC,
                    title = name,
                    versionCounter = 1,
                    communityKey = communityKey,
                )
            } catch (_: Exception) {
                // best-effort — the chat is already local; directory publish is optional
            }
        }

        openGroupChat(chatId, name)
        return chatId
    }

    /**
     * Open an existing group chat by [chatId]. Loads the key, loads the roster from the directory,
     * and switches the active send-path to this chat.
     */
    suspend fun openGroupChat(
        chatId: String,
        chatName: String,
    ) {
        val chatKey = crypto.chatKeyStore(requireContext()).load(chatId) ?: return
        val graph = runtimeGraph() ?: return
        // Roster: self + all community members from the directory.
        val roster = mutableListOf(graph.senderIdentifier)
        val memberNames = mutableMapOf<String, String>()
        val stored = configStore.loadStored(currentCommunityId)
        if (stored != null) {
            try {
                val chatKeyForDir = crypto.chatKeyStore(requireContext()).load(stored.chatId)
                if (chatKeyForDir != null) {
                    val service =
                        directoryFactory.directoryService(
                            baseUrl = stored.config.baseUrl,
                            username = stored.config.username,
                            appPassword = stored.config.appPassword,
                            communityRoot = stored.config.chatRoot,
                        )
                    val entries = service.readDirectory(chatKeyForDir).entries
                    for (entry in entries) {
                        val memberHex = Hex.encode(entry.copySigningPublicKey())
                        if (memberHex != graph.senderIdentifier) {
                            roster.add(memberHex)
                        }
                        memberNames[memberHex] = entry.displayName
                    }
                }
            } catch (_: Exception) {
                // best-effort roster — start with just self
            }
        }
        EngineWiring.switchToChat(chatId, chatName, chatKey, roster, memberNames)
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
                    // Only add if not already registered locally.
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

    /** Observable count of unread messages for a chat. Falls back to 0 if no engine is active. */
    fun observeUnreadCount(chatId: String): Flow<Int> = runtimeGraph()?.store?.observeUnreadCount(chatId) ?: flowOf(0)

    /** Switch the active community to [communityId] — rebuilds the engine for that community. */
    fun switchToCommunity(communityId: String) {
        val stored = configStore.loadStored(communityId) ?: return
        val chatKeyStore = crypto.chatKeyStore(requireContext())
        val chatKey = chatKeyStore.load(stored.chatId) ?: return
        val identity = runBlocking { identityFactory.identityStore(requireContext()).loadOrCreate() }
        currentCommunityId = communityId
        EngineWiring.reconfigure(
            config = stored.config,
            chatId = stored.chatId,
            communityName = stored.communityName,
            chatKey = chatKey,
            identity = identity,
            communityId = communityId,
        )
        refreshMemberNames()
    }

    /**
     * Start a DM with [peer] in the current community. Derives the deterministic DM chat-id from the
     * two box public keys, provisions the per-pair DH key, registers the chat, and switches to it.
     * Returns the DM chat-id on success, or `null` if the graph is absent / provision fails.
     */
    fun startDm(peer: DirectoryEntry): String? {
        val graph = runtimeGraph() ?: return null
        val identity = graph.identity
        val myBoxPub = identity.copyBoxPublic()
        val peerBoxPub = peer.copyBoxPublicKey()
        val chatId = ChatIds.dmChatId(crypto.nativeCrypto(), myBoxPub, peerBoxPub)

        // Provision the per-pair DH key (idempotent — re-provisioning derives the same key).
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
        chatRegistry.add(currentCommunityId, ChatRegistry.Entry(chatId, peer.displayName, "dm"))

        // DM roster: just the two participants (self + peer). The peer's on-disk identifier
        // is the hex of their Ed25519 signing public key.
        val peerId = Hex.encode(peer.copySigningPublicKey())

        // Switch the active send path to the DM chat.
        switchToChat(chatId, peer.displayName, peerId)

        return chatId
    }

    /**
     * Switch the active send-path chat within the current community. Loads the per-chat key from the
     * Keystore and builds a new [RuntimeGraph] with the DM roster = [self, peerId].
     */
    private fun switchToChat(
        chatId: String,
        chatName: String,
        peerId: String,
    ) {
        val chatKey = crypto.chatKeyStore(requireContext()).load(chatId) ?: return
        val graph = runtimeGraph() ?: return
        val roster = listOf(graph.senderIdentifier, peerId)
        val memberNames = mapOf(peerId to chatName)
        EngineWiring.switchToChat(chatId, chatName, chatKey, roster, memberNames)
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

    /**
     * Refresh [RuntimeGraph.memberNames] from the on-disk directory for the current community.
     * Best-effort, async — failures are silently ignored; member names appear on the next successful read.
     */
    private fun refreshMemberNames() {
        appScope.launch {
            try {
                val names = loadMemberNames()
                val graph = runtimeGraph()
                if (names.isNotEmpty()) {
                    graph?.memberNames = names
                    graph?.setMemberNamesError(null)
                } else {
                    graph?.setMemberNamesError("Member names not available — showing key prefixes")
                }
            } catch (e: Exception) {
                runtimeGraph()?.setMemberNamesError("Couldn't load member names: ${e.message}")
            }
        }
    }

    /**
     * Write community metadata (poll floor + retention window) to `meta/community.json` on the WebDAV
     * disk, signed by the host identity. Best-effort — failures are silently ignored; the next poll
     * cycle will re-read the current value from the disk.
     *
     * Also updates the local UserSettings cache immediately so the settings UI reflects the new values
     * without waiting for the next poll cycle.
     */
    fun updateCommunityMetadata(
        retentionDays: Int,
        pollSeconds: Int,
        onError: ((String) -> Unit)? = null,
    ) {
        val graph = runtimeGraph() ?: return
        appScope.launch {
            try {
                val transport = TransportFactory.create(graph.config)
                val metadata =
                    CommunityMetadata(
                        minPollIntervalSeconds = pollSeconds,
                        retentionWindowDays = retentionDays,
                    )
                CommunityMetadata.write(
                    transport = transport,
                    metadata = metadata,
                    hostIdentity = graph.identity,
                    identityCrypto = identityFactory.identityCrypto(),
                )
                // Update local cache immediately so the UI reflects the change.
                UserSettings.communityMinPollSeconds = pollSeconds
                UserSettings.communityRetentionWindowDays = retentionDays
            } catch (e: Exception) {
                onError?.invoke("Couldn't save settings: ${e.message}")
            }
        }
    }

    /** Whether the current user is the host of the active community. */
    val isHost: Boolean get() = UserSettings.isHost

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
        val graph = runtimeGraph() ?: return false
        val stored = configStore.loadStored(activeCommunityId) ?: return false
        val chatKey = crypto.chatKeyStore(requireContext()).load(stored.chatId) ?: return false

        // Read verified members from the directory.
        val service =
            directoryFactory.directoryService(
                baseUrl = stored.config.baseUrl,
                username = stored.config.username,
                appPassword = stored.config.appPassword,
                communityRoot = stored.config.chatRoot,
            )
        val members =
            try {
                service.readDirectory(chatKey).entries
            } catch (_: Exception) {
                return false
            }

        val idCrypto = identityFactory.identityCrypto()
        val hostIdentity = graph.identity
        val newConfig =
            ConnectionConfig(
                baseUrl = newUrl,
                username = newUsername,
                appPassword = newPassword,
                chatRoot = stored.config.chatRoot,
            )
        val transport = TransportFactory.create(graph.config)

        // Ensure meta/credentials/ exists.
        transport.ensureCollection("meta/credentials")

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
                        hostIdentity = hostIdentity,
                    )
                val path = "meta/credentials/$memberHex"
                val result = transport.write(path, blob)
                if (result !is WebDavResult.Success) {
                    allOk = false
                }
            } catch (_: Exception) {
                allOk = false
            }
        }

        // Update local config so the host uses the new credential.
        if (allOk) {
            configStore.save(
                newConfig,
                stored.chatId,
                stored.communityName,
                communityId = activeCommunityId,
            )
            EngineWiring.reconfigure(
                config = newConfig,
                chatId = stored.chatId,
                communityName = stored.communityName,
                chatKey = chatKey,
                identity = hostIdentity,
                communityId = activeCommunityId,
            )
        }
        return allOk
    }

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
                // Use chatId as communityId for simplicity — one community = one chat.
                configStore.save(config, chatId, communityName, communityId = chatId)
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
                UserSettings.isHost = isHost
                EngineWiring.reconfigure(config, chatId, communityName, chatKey, identity, communityId = chatId)
                // Write on-disk metadata (async, best-effort).
                appScope.launch {
                    try {
                        val transport = TransportFactory.create(config)
                        // Register ourselves in the disk roster.
                        RosterService(transport).addMyself(
                            org.openwebdav.messenger.protocol.Hex.encode(identity.copySignPublic()),
                        )
                        // The host writes the community metadata (polling floor, etc.).
                        if (isHost) {
                            val metadata =
                                CommunityMetadata(
                                    minPollIntervalSeconds = CommunityMetadata.DEFAULT_FLOOR_SECONDS,
                                )
                            CommunityMetadata.write(
                                transport = transport,
                                metadata = metadata,
                                hostIdentity = identity,
                                identityCrypto = identityFactory.identityCrypto(),
                            )
                        }
                    } catch (_: Exception) {
                        // best-effort
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

    private const val CHAT_ID_RANDOM_BYTES = 16
    private const val CHAT_ID_CHARS = 26
}
