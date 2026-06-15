package org.openwebdav.messenger.ui.onboarding

import android.os.Looper
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
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
 * Compose `createComposeRule` UI test for the owner connect+create screen (`ui-chat-surface` plan Test plan;
 * Scenario 1). Drives the screen's hoisted state through a real [CreateCommunityViewModel] backed by a
 * recording onboarding service (no device-bound Keystore): the Create button gates on completeness, a
 * non-HTTPS URL surfaces the inline SC13 refusal, and a valid HTTPS form navigates onward. UI logic only —
 * all I/O/KDF stays in the ViewModel off the UI thread (stack-notes Compose).
 * Source: <https://developer.android.com/develop/ui/compose/testing>
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CreateCommunityScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    // viewModelScope launches on Dispatchers.Main; pin it to Unconfined so the submit coroutine runs
    // inline through the Unconfined onboarding service (no separate looper round-trip to drain).
    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(deps: RecordingOnboardingDeps) =
        CreateCommunityViewModel(OnboardingService(deps, ioDispatcher = Dispatchers.Unconfined))

    /**
     * The ViewModel's submit runs on `viewModelScope` (the main Looper); `idle()` drains that Looper so the
     * onboarding result lands, then `waitForIdle()` re-syncs Compose so the state change is composed.
     */
    private fun drainMainLooper() {
        repeat(DRAIN_PASSES) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.waitForIdle()
        }
    }

    private fun fillValidForm(scheme: String) {
        composeRule.onNodeWithContentDescription("Disk address").performTextInput("${scheme}disk.example.test")
        composeRule.onNodeWithContentDescription("Login").performTextInput("owner")
        composeRule.onNodeWithContentDescription("App password").performTextInput("fake-app-password-not-real")
        composeRule.onNodeWithContentDescription("Community name").performTextInput("My Community")
    }

    /** The Create button is disabled until the required fields are filled, then enabled. */
    @Test
    fun create_button_gates_on_form_completeness() {
        val deps = RecordingOnboardingDeps(AppTestSupport.newIdentity())
        composeRule.setContent {
            CreateCommunityScreen(onCreated = {}, onBack = {}, viewModel = viewModel(deps))
        }

        composeRule.onNodeWithContentDescription("Create community").assertIsNotEnabled()
        fillValidForm("https://")
        composeRule.onNodeWithContentDescription("Create community").assertIsEnabled()
    }

    /** A non-HTTPS URL is refused inline with the plain-language SC13 message; nothing is persisted. */
    @Test
    fun cleartext_url_shows_inline_refusal_and_does_not_navigate() {
        val deps = RecordingOnboardingDeps(AppTestSupport.newIdentity())
        var created = false
        composeRule.setContent {
            CreateCommunityScreen(onCreated = { created = true }, onBack = {}, viewModel = viewModel(deps))
        }

        fillValidForm("http://")
        composeRule.onNodeWithContentDescription("Create community").performScrollTo().performClick()
        drainMainLooper()

        composeRule.onNodeWithText(CreateCommunityViewModel.HTTPS_REQUIRED_MESSAGE).assertExists()
        assert(!created) { "a cleartext URL must not navigate to the feed" }
        assert(deps.savedConfig == null) { "nothing is persisted on an SC13 refusal" }
    }

    /** A valid HTTPS form creates the community and fires onCreated (navigation to the feed). */
    @Test
    fun valid_https_form_creates_and_navigates() {
        val deps = RecordingOnboardingDeps(AppTestSupport.newIdentity())
        var created = false
        composeRule.setContent {
            CreateCommunityScreen(onCreated = { created = true }, onBack = {}, viewModel = viewModel(deps))
        }

        fillValidForm("https://")
        composeRule.onNodeWithContentDescription("Create community").performScrollTo().performClick()
        drainMainLooper()

        assert(deps.savedConfig?.baseUrl == "https://disk.example.test") { "the HTTPS config must be persisted" }
        assert(created) { "a valid HTTPS create must navigate to the feed" }
    }

    private companion object {
        // A few drain/sync passes so the viewModelScope coroutine fully settles before assertions.
        const val DRAIN_PASSES = 5
    }
}
