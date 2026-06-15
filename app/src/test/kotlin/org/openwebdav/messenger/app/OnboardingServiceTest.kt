package org.openwebdav.messenger.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.KeySources
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.transport.ConnectionConfig

/**
 * JVM unit tests for [OnboardingService] — owner create-community, the SC13 cleartext refusal, and the
 * silent member join (`ui-chat-surface` plan Test plan). Real libsodium-backed [KeySources] (random key,
 * raw import) + an in-memory chat-key store + a recording config/reconfigure seam stand in for the
 * device-bound stores. All NEW tests; no existing test or substrate touched.
 */
class OnboardingServiceTest {
    /** A recording [OnboardingService.Deps] — captures what was persisted + reconfigured. */
    private class RecordingDeps(
        val identity: Identity,
        val chatIdToMint: String = "minted-chat-id-0000000001",
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

        override suspend fun checkFolder(
            config: ConnectionConfig,
            root: String,
        ): OnboardingService.FolderCheck = OnboardingService.FolderCheck.Ok
    }

    private fun service(deps: OnboardingService.Deps) = OnboardingService(deps, ioDispatcher = Dispatchers.Unconfined)

    /**
     * owner_create_community_persists_keystore_wrapped_auto_creates_chat_and_installs_runner — create
     * auto-creates the one chat (no separate step), stores config + the random key, and reconfigures the
     * engine (the install/schedule happens inside reconfigure, asserted in EngineWiringTest).
     */
    @Test
    fun owner_create_community_persists_and_auto_creates_chat_and_reconfigures() =
        runTest {
            val deps = RecordingDeps(AppTestSupport.newIdentity())
            val result =
                service(deps).createCommunity(
                    baseUrl = "https://disk.example.test",
                    username = "owner",
                    appPassword = "fake-app-password-not-real",
                    chatRoot = "owdm/root",
                    communityName = "My Community",
                )

            assertTrue(result is OnboardingService.CreateResult.Created)
            val created = result as OnboardingService.CreateResult.Created
            // The one community chat is the minted chat-id — no separate create-chat step.
            assertEquals(deps.chatIdToMint, created.chatId)
            assertEquals("My Community", created.communityName)
            // Config + the random chat key are persisted; the key is in the (in-memory stand-in) store.
            assertEquals("https://disk.example.test", deps.savedConfig!!.baseUrl)
            assertEquals(deps.chatIdToMint, deps.savedChatId)
            assertTrue(deps.chatKeyStore.has(deps.chatIdToMint))
            // The engine was reconfigured for that chat with the same key.
            assertEquals(deps.chatIdToMint, deps.reconfiguredChatId)
            assertNotNull(deps.reconfiguredKey)
        }

    /** owner_connect_cleartext_url_is_refused — an http:// URL is refused, NOTHING is persisted (SC13). */
    @Test
    fun owner_connect_cleartext_url_is_refused() =
        runTest {
            val deps = RecordingDeps(AppTestSupport.newIdentity())
            val result =
                service(deps).createCommunity(
                    baseUrl = "http://disk.example.test",
                    username = "owner",
                    appPassword = "fake-app-password-not-real",
                    chatRoot = "owdm/root",
                    communityName = "My Community",
                )

            assertTrue(result is OnboardingService.CreateResult.CleartextRefused)
            // Nothing persisted, nothing reconfigured.
            assertNull(deps.savedConfig)
            assertNull(deps.reconfiguredChatId)
            assertFalse(deps.chatKeyStore.has(deps.chatIdToMint))
        }

    /**
     * member_join_from_invite_configures_silently_without_exposing_credentials — a valid invite configures
     * the member silently; the JoinResult carries ONLY the community name + chat-id (no disk credentials).
     */
    @Test
    fun member_join_from_invite_configures_silently_without_exposing_credentials() =
        runTest {
            // The owner mints a key + an invite carrying the disk config (the member never types these).
            val ownerConfig = AppTestSupport.httpsConfig()
            val chatId = "shared-chat-id-000000000001"
            val key = AppTestSupport.keySources().newRandomKey()
            val invite = AppTestSupport.inviteString(ownerConfig, chatId, key, "Owner's Community")

            val deps = RecordingDeps(AppTestSupport.newIdentity())
            val result = service(deps).joinFromInvite(invite)

            assertTrue(result is OnboardingService.JoinResult.Joined)
            val joined = result as OnboardingService.JoinResult.Joined
            assertEquals(chatId, joined.chatId)
            assertEquals("Owner's Community", joined.communityName)
            // The member's app is configured silently with the disk config from the invite...
            assertEquals(ownerConfig.baseUrl, deps.savedConfig!!.baseUrl)
            assertEquals(ownerConfig.appPassword, deps.savedConfig!!.appPassword)
            assertTrue(deps.chatKeyStore.has(chatId))
            // ...and the imported key matches what the owner put in the invite (byte-identical).
            assertArrayEqualsKey(key, deps.chatKeyStore.load(chatId))
            // The Joined result exposes NO disk fields — only the community name + chat-id.
            // (Enforced structurally: JoinResult.Joined has only chatId + communityName.)
        }

    /** member_join rejects a foreign / garbled invite with a clean Invalid (no crash, no partial config). */
    @Test
    fun member_join_rejects_invalid_invite() =
        runTest {
            val deps = RecordingDeps(AppTestSupport.newIdentity())
            val result = service(deps).joinFromInvite("not-an-owdm-invite")

            assertTrue(result is OnboardingService.JoinResult.Invalid)
            assertNull(deps.savedConfig)
            assertNull(deps.reconfiguredChatId)
        }

    private fun assertArrayEqualsKey(
        expected: ChatKey,
        actual: ChatKey?,
    ) {
        assertNotNull(actual)
        assertTrue(expected.export().contentEquals(actual!!.export()))
    }
}
