package org.openwebdav.messenger.app

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.openwebdav.messenger.chatdirectory.ChatAccess
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.KeySources
import org.openwebdav.messenger.crypto.LazySodiumCrypto
import org.openwebdav.messenger.crypto.MessageCrypto
import org.openwebdav.messenger.crypto.NativeCrypto
import org.openwebdav.messenger.data.MessengerDatabase
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.identity.IdentityTestSupport
import org.openwebdav.messenger.invite.InviteCodec
import org.openwebdav.messenger.invite.InviteToken
import org.openwebdav.messenger.keystore.ChatKeyStorePort
import org.openwebdav.messenger.keystore.StoredConnection
import org.openwebdav.messenger.message.MessageEnvelope
import org.openwebdav.messenger.protocol.Hex
import org.openwebdav.messenger.sync.SyncEngine
import org.openwebdav.messenger.sync.SyncTestSupport
import org.openwebdav.messenger.transport.ConnectionConfig
import org.openwebdav.messenger.transport.Delayer
import java.util.concurrent.ConcurrentHashMap

/**
 * Real, libsodium-backed substrates + JVM in-memory store stand-ins for the `app/` layer JVM tests
 * (`ui-chat-surface` plan Test plan). The Keystore-backed stores run only under `connectedAndroidTest`, so
 * here [InMemoryChatKeyStore] / [RecordingConfigStore] stand in for them via the same narrow seams. All
 * NEW test support; no existing test or production code touched.
 */
internal object AppTestSupport {
    private val sodium: LazySodiumJava by lazy { LazySodiumJava(SodiumJava()) }

    fun native(): NativeCrypto = LazySodiumCrypto(sodium)

    fun keySources(): KeySources = KeySources(native())

    fun identityCrypto(): IdentityCrypto = IdentityCrypto(native())

    fun newIdentity(): Identity = identityCrypto().generateIdentity()

    fun recipientRosterTestGraph(
        server: MockWebServer,
        database: MessengerDatabase,
        readiness: RecipientReadiness = RecipientReadiness.Loading,
        chatId: String = "group-a",
        communityName: String = "Group A",
        communityId: String = "community-a",
        config: ConnectionConfig = SyncTestSupport.config(server),
        key: ChatKey = SyncTestSupport.fixedChatKey(),
        identity: Identity = newIdentity(),
        privateMembershipChat: Boolean = false,
    ): RuntimeGraph {
        val store = SyncTestSupport.store(database, communityId)
        val envelope = MessageEnvelope.create(MessageCrypto(Aead(native())), identityCrypto())
        val engine = SyncEngine(SyncTestSupport.transport(server), envelope, store, { key })
        return RuntimeGraph(
            engine, store, envelope, config, chatId, communityName, key,
            identity, Hex.encode(identity.copySignPublic()), communityId = communityId,
            privateMembershipChat = privateMembershipChat,
            initialRecipientReadiness = readiness,
        )
    }

    fun chatOpenTestDeps(
        server: MockWebServer,
        database: MessengerDatabase,
        communityId: String,
        stored: StoredConnection,
        identity: Identity,
        chatKeys: Map<String, ChatKey>,
        rawFileRead: suspend () -> ByteArray? = { null },
        privateMembershipChat: Boolean = false,
    ): EngineWiring.Deps {
        val testCommunityId = communityId
        return object : EngineWiring.Deps {
            override fun loadStoredConnection(): StoredConnection = stored

            override fun activeCommunityId(): String = communityId

            override fun loadStoredConnection(communityId: String): StoredConnection? = stored.takeIf { communityId == testCommunityId }

            override fun loadChatKey(chatId: String): ChatKey? = chatKeys[chatId]

            override fun loadIdentity(): Identity = identity

            override fun identityCrypto(): IdentityCrypto = AppTestSupport.identityCrypto()

            override suspend fun readRawFile(
                config: ConnectionConfig,
                path: String,
            ): ByteArray? = rawFileRead()

            override fun saveRotatedConfig(
                newConfig: ConnectionConfig,
                communityId: String,
            ): Boolean = false

            override fun buildGraph(
                config: ConnectionConfig,
                chatId: String,
                communityName: String,
                chatKey: ChatKey,
                identity: Identity,
                communityId: String,
            ): RuntimeGraph =
                recipientRosterTestGraph(
                    server = server,
                    database = database,
                    readiness = RecipientReadiness.Ready(emptyList()),
                    chatId = chatId,
                    communityName = communityName,
                    communityId = communityId,
                    config = config,
                    key = chatKey,
                    identity = identity,
                    privateMembershipChat = privateMembershipChat,
                )

            override fun communityChatIds(communityId: String): List<String> = chatKeys.keys.toList()

            override suspend fun discoverPublicChats() = Unit

            override fun schedulePoll(communityMinPollSeconds: Int?) = Unit
        }
    }

    fun emptyEngineDeps(): EngineWiring.Deps =
        object : EngineWiring.Deps {
            override fun loadStoredConnection(): StoredConnection? = null

            override fun loadChatKey(chatId: String): ChatKey? = null

            override fun loadIdentity(): Identity? = null

            override fun identityCrypto(): IdentityCrypto = AppTestSupport.identityCrypto()

            override suspend fun readRawFile(
                config: ConnectionConfig,
                path: String,
            ): ByteArray? = null

            override fun saveRotatedConfig(
                newConfig: ConnectionConfig,
                communityId: String,
            ): Boolean = false

            override fun buildGraph(
                config: ConnectionConfig,
                chatId: String,
                communityName: String,
                chatKey: ChatKey,
                identity: Identity,
                communityId: String,
            ): RuntimeGraph = error("No runtime graph expected")

            override fun communityChatIds(communityId: String): List<String> = emptyList()

            override fun schedulePoll(communityMinPollSeconds: Int?) = Unit
        }

    /** Obvious-fake HTTPS config (SC21 — no real credentials). */
    fun httpsConfig(): ConnectionConfig =
        ConnectionConfig(
            baseUrl = "https://disk.example.test",
            username = "owner",
            appPassword = "fake-app-password-not-real",
            chatRoot = "owdm/root",
        )

    fun inviteCodec(): InviteCodec = InviteCodec(IdentityTestSupport.identityCrypto())

    /** Build an owdm1: invite string from a config + random key + chat-id + name (for join tests). */
    suspend fun inviteString(
        config: ConnectionConfig,
        chatId: String,
        chatKey: ChatKey,
        communityName: String,
        access: ChatAccess = ChatAccess.PUBLIC,
    ): String {
        val idCrypto = IdentityTestSupport.identityCrypto()
        val identity = idCrypto.generateIdentity()
        val codec = InviteCodec(idCrypto)
        val token =
            InviteToken(
                baseUrl = config.baseUrl,
                username = config.username,
                appPassword = config.appPassword,
                chatRoot = config.chatRoot,
                chatId = chatId,
                chatKey = chatKey.export(),
                communityName = communityName,
                access = access,
                signingPublicKey = identity.copySignPublic(),
                signature = ByteArray(InviteToken.SIGNATURE_BYTES),
            )
        val secret = identity.copySignSecret()
        val signed =
            try {
                codec.sign(token, secret)
            } finally {
                secret.fill(0)
            }
        return codec.encode(signed)
    }

    fun testClient(): OkHttpClient = OkHttpClient.Builder().build()

    fun instantDelayer(): Delayer = Delayer { /* no delay in tests */ }
}

/**
 * A reusable recording [OnboardingService.Deps] for the ViewModel/onboarding JVM tests — captures what was
 * persisted + reconfigured, backed by real libsodium [KeySources] + an in-memory chat-key store. Mirrors the
 * device-bound seams without the Keystore (which runs only under `connectedAndroidTest`). Used by the
 * `JoinViewModel` / `CreateCommunityViewModel` Compose tests; `OnboardingServiceTest` keeps its own copy.
 */
internal class RecordingOnboardingDeps(
    private val identity: Identity,
    private val chatIdToMint: String = "minted-chat-id-0000000001",
) : OnboardingService.Deps {
    val chatKeyStore = InMemoryChatKeyStore()
    var savedConfig: ConnectionConfig? = null
    var savedChatId: String? = null
    var savedCommunityName: String? = null
    var reconfiguredChatId: String? = null
    var reconfiguredKey: ChatKey? = null

    override fun keySources(): KeySources = AppTestSupport.keySources()

    override fun chatKeyStore() = chatKeyStore

    override fun saveConfig(
        config: ConnectionConfig,
        chatId: String,
        communityName: String,
        access: String,
    ) {
        savedConfig = config
        savedChatId = chatId
        savedCommunityName = communityName
    }

    override suspend fun ensureIdentity(): Identity = identity

    override fun newChatId(): String = chatIdToMint

    override fun reconfigure(
        config: ConnectionConfig,
        chatId: String,
        communityName: String,
        chatKey: ChatKey,
        identity: Identity,
        isHost: Boolean,
    ) {
        reconfiguredChatId = chatId
        reconfiguredKey = chatKey
    }

    override suspend fun checkFolder(
        config: ConnectionConfig,
        root: String,
    ): OnboardingService.FolderCheck = OnboardingService.FolderCheck.Ok
}

/** JVM in-memory [ChatKeyStorePort] — the same seam `ChatKeyStore` implements on device. */
internal class InMemoryChatKeyStore : ChatKeyStorePort {
    private val keys = ConcurrentHashMap<String, ByteArray>()

    override fun store(
        chatId: String,
        chatKey: ChatKey,
    ) {
        keys[chatId] = chatKey.export()
    }

    override fun load(chatId: String): ChatKey? = keys[chatId]?.let { ChatKey.fromBytes(it) }

    fun has(chatId: String): Boolean = keys.containsKey(chatId)
}
