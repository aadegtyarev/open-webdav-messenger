package org.openwebdav.messenger.ui.onboarding

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.openwebdav.messenger.app.AppTestSupport
import org.openwebdav.messenger.app.OnboardingService
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.KeySources
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.keystore.ChatKeyStorePort
import org.openwebdav.messenger.transport.ConnectionConfig

/**
 * Failure-path test for [CreateCommunityViewModel] (review finding 5): a Keystore/TransportFactory/IO throw
 * during create must clear `submitting` and surface a plain-language error, not hang forever or crash. The
 * HTTPS refusal path (its own typed result) is covered by `CreateCommunityScreenTest`; this covers the throw.
 * All NEW; no existing test touched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreateCommunityViewModelFailureTest {
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** A valid HTTPS create whose work throws surfaces CREATE_FAILED_MESSAGE and clears submitting. */
    @Test
    fun create_that_throws_surfaces_error_and_clears_submitting() =
        runTest(mainDispatcher) {
            val service = OnboardingService(ThrowingDeps(), ioDispatcher = Dispatchers.Unconfined)
            val vm = CreateCommunityViewModel(service)

            vm.onBaseUrl("https://disk.example.test")
            vm.onUsername("owner")
            vm.onAppPassword("fake-app-password-not-real")
            vm.onCommunityName("My Community")

            var created = false
            vm.submit { created = true }
            advanceUntilIdle()

            assertFalse("a failing create must not navigate", created)
            val state = vm.state.first()
            assertEquals(CreateCommunityViewModel.CREATE_FAILED_MESSAGE, state.generalError)
            assertFalse("submitting flag must be cleared after a failure", state.submitting)
        }

    /** A [OnboardingService.Deps] whose identity-ensure throws — stands in for a Keystore/IO/transport failure. */
    private class ThrowingDeps : OnboardingService.Deps {
        override fun keySources(): KeySources = AppTestSupport.keySources()

        override fun chatKeyStore(): ChatKeyStorePort = error("not reached")

        override fun saveConfig(
            config: ConnectionConfig,
            chatId: String,
            communityName: String,
        ) = error("not reached")

        override suspend fun ensureIdentity(): Identity = throw IllegalStateException("Keystore / transport failure")

        override fun newChatId(): String = "minted-chat-id-0000000001"

        override fun reconfigure(
            config: ConnectionConfig,
            chatId: String,
            communityName: String,
            chatKey: ChatKey,
            identity: Identity,
        ) = error("not reached")

        override suspend fun checkFolder(
            config: ConnectionConfig,
            root: String,
        ): OnboardingService.FolderCheck = error("not reached")
    }
}
