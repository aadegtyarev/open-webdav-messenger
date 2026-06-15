package org.openwebdav.messenger.app

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.KeySources
import org.openwebdav.messenger.crypto.LazySodiumCrypto
import org.openwebdav.messenger.crypto.NativeCrypto
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.invite.InviteCodec
import org.openwebdav.messenger.invite.InviteToken
import org.openwebdav.messenger.keystore.ChatKeyStorePort
import org.openwebdav.messenger.transport.ConnectionConfig
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

    /** Obvious-fake HTTPS config (SC21 — no real credentials). */
    fun httpsConfig(): ConnectionConfig =
        ConnectionConfig(
            baseUrl = "https://disk.example.test",
            username = "owner",
            appPassword = "fake-app-password-not-real",
            chatRoot = "owdm/root",
        )

    /** Build an owdm1: invite string from a config + random key + chat-id + name (for join tests). */
    suspend fun inviteString(
        config: ConnectionConfig,
        chatId: String,
        chatKey: ChatKey,
        communityName: String,
    ): String =
        InviteCodec().encode(
            InviteToken(
                baseUrl = config.baseUrl,
                username = config.username,
                appPassword = config.appPassword,
                chatRoot = config.chatRoot,
                chatId = chatId,
                chatKey = chatKey.export(),
                communityName = communityName,
            ),
        )
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
    ) {
        reconfiguredChatId = chatId
        reconfiguredKey = chatKey
    }
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
