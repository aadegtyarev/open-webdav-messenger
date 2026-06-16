package org.openwebdav.messenger.app

import android.content.Context
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    private val warmStarted = AtomicBoolean(false)

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
                AndroidDeps(requireContext(), crypto, identityFactory, configStore, chatRegistry),
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

    /** The composed onboarding service (owner-create / member-join). */
    fun onboarding(): OnboardingService = OnboardingService(productionOnboardingDeps())

    /** All joined communities from the registry. */
    fun communities(): List<CommunityRegistry.Entry> = communityRegistry.all()

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
        val stored = configStore.loadStored(communityId) ?: return emptyList()
        val chatKey = crypto.chatKeyStore(requireContext()).load(stored.chatId) ?: return emptyList()
        val service =
            directoryFactory.directoryService(
                baseUrl = stored.config.baseUrl,
                username = stored.config.username,
                appPassword = stored.config.appPassword,
                communityRoot = stored.config.chatRoot,
            )
        return try {
            service.readDirectory(chatKey).entries
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** The live engine graph for the joined chat, or `null` if nothing is joined yet. */
    fun runtimeGraph(): RuntimeGraph? = EngineWiring.current()

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
        pollMinutes: Int,
    ) {
        val graph = runtimeGraph() ?: return
        GlobalScope.launch {
            try {
                val transport = TransportFactory.create(graph.config)
                val metadata =
                    CommunityMetadata(
                        minPollIntervalMinutes = pollMinutes,
                        retentionWindowDays = retentionDays,
                    )
                CommunityMetadata.write(
                    transport = transport,
                    metadata = metadata,
                    hostIdentity = graph.identity,
                    identityCrypto = identityFactory.identityCrypto(),
                )
                // Update local cache immediately so the UI reflects the change.
                UserSettings.communityMinPollMinutes = pollMinutes
                UserSettings.communityRetentionWindowDays = retentionDays
            } catch (_: Exception) {
                // best-effort — the next poll cycle will re-read the current value from disk
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
                kotlinx.coroutines.GlobalScope.launch {
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
                                    minPollIntervalMinutes = CommunityMetadata.DEFAULT_FLOOR_MINUTES,
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
