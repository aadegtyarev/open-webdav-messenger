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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.openwebdav.messenger.app.AppTestSupport
import org.openwebdav.messenger.app.OnboardingService
import org.openwebdav.messenger.app.RecordingOnboardingDeps

/**
 * JVM tests for [JoinViewModel] (`ui-chat-surface` plan Interaction scenario `camera_denied_falls_back_to_paste`
 * + contract `community-chat.md` Acceptance check). The decisive guarantee: a member with **no camera or a
 * denied camera permission** can still join, because the paste path is the mandated fallback and is wired
 * through the SAME [OnboardingService.joinFromInvite] decode/persist path as a scan. The live camera decode
 * is a manual on-device step (stack-notes QR-scan); here we drive only the paste-accept logic the screen's
 * always-present field calls — independent of the camera. All NEW; no existing test touched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JoinViewModelTest {
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun service(deps: RecordingOnboardingDeps) = OnboardingService(deps, ioDispatcher = Dispatchers.Unconfined)

    /**
     * camera_denied_falls_back_to_paste — with the camera unavailable/denied (the scan path never fires), a
     * pasted valid invite still joins: the config is persisted silently and the ViewModel signals success.
     */
    @Test
    fun camera_denied_falls_back_to_paste() =
        runTest(mainDispatcher) {
            // The owner minted an invite; the member only ever pastes it (no camera in this flow).
            val key = AppTestSupport.keySources().newRandomKey()
            val invite = AppTestSupport.inviteString(AppTestSupport.httpsConfig(), "paste-chat-000000000001", key, "Pasted Community")

            val deps = RecordingOnboardingDeps(AppTestSupport.newIdentity())
            val vm = JoinViewModel(service(deps))

            vm.onPasted(invite)
            var joined = false
            // joinFromPaste is the field's accept action — the camera-less fallback the screen always offers.
            vm.joinFromPaste { joined = true }
            advanceUntilIdle()

            assertTrue("paste fallback must complete the join with no camera involved", joined)
            // The join configured the member silently via the same path a scan would take.
            assertEquals("https://disk.example.test", deps.savedConfig!!.baseUrl)
            assertTrue(deps.chatKeyStore.has("paste-chat-000000000001"))
            // No error surfaced; the in-progress flag is cleared.
            val state = vm.state.first()
            assertNull(state.error)
            assertTrue(!state.joining)
        }

    /** A garbled pasted invite surfaces the plain-language error and persists nothing (Scenario 4 / paste path). */
    @Test
    fun pasted_invalid_invite_shows_error_and_persists_nothing() =
        runTest(mainDispatcher) {
            val deps = RecordingOnboardingDeps(AppTestSupport.newIdentity())
            val vm = JoinViewModel(service(deps))

            vm.onPasted("not-an-owdm-invite")
            var joined = false
            vm.joinFromPaste { joined = true }
            advanceUntilIdle()

            assertTrue("invalid invite must not navigate", !joined)
            assertEquals(JoinViewModel.INVALID_INVITE_MESSAGE, vm.state.first().error)
            assertNull(deps.savedConfig)
        }
}
