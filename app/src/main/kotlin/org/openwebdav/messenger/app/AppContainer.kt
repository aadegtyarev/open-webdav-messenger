package org.openwebdav.messenger.app

import android.content.Context
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.CryptoFactory
import org.openwebdav.messenger.crypto.KeySources
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.identity.IdentityFactory
import org.openwebdav.messenger.keystore.ChatKeyStorePort
import org.openwebdav.messenger.keystore.ConnectionConfigStore
import org.openwebdav.messenger.protocol.Base32
import org.openwebdav.messenger.transport.ConnectionConfig
import java.security.SecureRandom

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

    private val crypto by lazy { CryptoFactory() }
    private val identityFactory by lazy { IdentityFactory() }

    /** Bind the application context (idempotent). Called from `Application.onCreate()` before [warmStart]. */
    fun bind(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /** Process-start engine wiring (reads the persisted config off the main thread; arch note Choice 1). */
    fun warmStart() {
        EngineWiring.initialize(requireContext())
    }

    /** The composed onboarding service (owner-create / member-join). */
    fun onboarding(): OnboardingService = OnboardingService(productionOnboardingDeps())

    /** The live engine graph for the joined chat, or `null` if nothing is joined yet. */
    fun runtimeGraph(): RuntimeGraph? = EngineWiring.current()

    private fun requireContext(): Context = appContext ?: error("AppContainer.bind(context) not called")

    /** Production [OnboardingService.Deps] — native crypto + Keystore-wrapped stores + the engine wiring. */
    private fun productionOnboardingDeps(): OnboardingService.Deps =
        object : OnboardingService.Deps {
            override fun keySources(): KeySources = crypto.keySources()

            override fun chatKeyStore(): ChatKeyStorePort = crypto.chatKeyStore(requireContext())

            override fun configStore(): ConnectionConfigStore = ConnectionConfigStore(requireContext())

            override suspend fun ensureIdentity(): Identity = identityFactory.identityStore(requireContext()).loadOrCreate()

            override fun newChatId(): String = randomChatId()

            override fun reconfigure(
                config: ConnectionConfig,
                chatId: String,
                communityName: String,
                chatKey: ChatKey,
                identity: Identity,
            ) {
                EngineWiring.reconfigure(config, chatId, communityName, chatKey, identity)
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
