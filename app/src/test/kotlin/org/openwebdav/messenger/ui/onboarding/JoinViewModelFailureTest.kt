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
 * Failure-path test for [JoinViewModel] (review finding 4): a structurally-valid invite to an
 * unreachable/failing disk throws during identity-ensure / Keystore wrap / engine build. The ViewModel must
 * clear `joining` and surface a plain-language error instead of hanging forever or crashing. All NEW; no
 * existing test touched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JoinViewModelFailureTest {
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** A valid invite whose application throws surfaces JOIN_FAILED_MESSAGE and clears the in-progress flag. */
    @Test
    fun valid_invite_that_fails_to_apply_surfaces_error_and_clears_joining() =
        runTest(mainDispatcher) {
            val key = AppTestSupport.keySources().newRandomKey()
            val invite = AppTestSupport.inviteString(AppTestSupport.httpsConfig(), "fail-chat-0000000000000001", key, "Will Fail")
            val service = OnboardingService(ThrowingDeps(), ioDispatcher = Dispatchers.Unconfined)
            val vm = JoinViewModel(service)

            vm.onPasted(invite)
            var joined = false
            vm.joinFromPaste { joined = true }
            advanceUntilIdle()

            assertFalse("a failing join must not navigate", joined)
            val state = vm.state.first()
            assertEquals(JoinViewModel.JOIN_FAILED_MESSAGE, state.error)
            assertFalse("joining flag must be cleared after a failure", state.joining)
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

        override suspend fun ensureIdentity(): Identity = throw IllegalStateException("disk unreachable / Keystore failure")

        override fun newChatId(): String = error("not reached")

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
