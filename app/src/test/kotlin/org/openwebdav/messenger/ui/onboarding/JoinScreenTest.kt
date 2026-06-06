package org.openwebdav.messenger.ui.onboarding

import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.app.AppTestSupport
import org.openwebdav.messenger.app.OnboardingService
import org.openwebdav.messenger.app.RecordingOnboardingDeps
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/**
 * Compose `createComposeRule` UI test for the member join screen (`ui-chat-surface` plan Test plan;
 * Scenarios 3–4, `camera_denied_falls_back_to_paste`). Two UI states are asserted: a **camera-less device**
 * (the default Robolectric package manager reports no camera) shows ONLY the always-present paste field +
 * Join button — never blocking the join — and a **camera-present device** additionally surfaces the optional
 * "Scan QR code" affordance. The live camera decode is a manual on-device step (stack-notes QR-scan); here
 * only the screen's paste/scan-entry UI logic is exercised. UI logic only — decode/persist is in the VM.
 * Source: <https://developer.android.com/develop/ui/compose/testing>
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class JoinScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun viewModel(deps: RecordingOnboardingDeps) = JoinViewModel(OnboardingService(deps, ioDispatcher = Dispatchers.Unconfined))

    /** No camera → only the paste field + Join button; the scan affordance is absent (paste is the fallback). */
    @Test
    fun no_camera_shows_only_paste_path() {
        val deps = RecordingOnboardingDeps(AppTestSupport.newIdentity())
        composeRule.setContent {
            JoinScreen(onJoined = {}, onBack = {}, viewModel = viewModel(deps))
        }

        composeRule.onNodeWithContentDescription("Invite string").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Join").assertIsDisplayed()
        // The optional scan affordance must NOT be present on a camera-less device.
        composeRule.onNodeWithContentDescription("Scan QR code").assertDoesNotExist()
    }

    /** A valid pasted invite joins through the always-present field (the camera-less fallback works). */
    @Test
    fun pasted_invite_joins_via_paste_field() {
        val deps = RecordingOnboardingDeps(AppTestSupport.newIdentity())
        val key = AppTestSupport.keySources()
        var joined = false
        composeRule.setContent {
            JoinScreen(onJoined = { joined = true }, onBack = {}, viewModel = viewModel(deps))
        }

        val invite =
            kotlinx.coroutines.runBlocking {
                AppTestSupport.inviteString(AppTestSupport.httpsConfig(), "ui-chat-000000000001", key.newRandomKey(), "Pasted")
            }
        composeRule.onNodeWithContentDescription("Invite string").performTextInput(invite)
        composeRule.onNodeWithContentDescription("Join").performScrollTo().performClick()
        // The join runs on viewModelScope (the main Looper); drain it, then re-sync Compose.
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()

        assert(joined) { "the paste path must complete the join without a camera" }
        assert(deps.savedConfig != null) { "the config was persisted silently from the pasted invite" }
    }

    /** A camera-present device additionally offers the optional "Scan QR code" affordance. */
    @Test
    fun camera_present_offers_scan_affordance() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, true)

        val deps = RecordingOnboardingDeps(AppTestSupport.newIdentity())
        composeRule.setContent {
            JoinScreen(onJoined = {}, onBack = {}, viewModel = viewModel(deps))
        }

        // Paste stays the primary, always-present path; scan is the optional enhancement on top.
        composeRule.onNodeWithContentDescription("Invite string").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Scan QR code").assertIsDisplayed()
    }
}
