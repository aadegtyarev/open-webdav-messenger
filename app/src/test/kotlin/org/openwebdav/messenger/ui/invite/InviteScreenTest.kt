package org.openwebdav.messenger.ui.invite

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Compose `createComposeRule` UI test for the invite-display screen (`ui-chat-surface` plan Test plan;
 * Scenario 2). The invite is the one security-bearing surface, so the load-bearing UI fact pinned here is
 * the **always-visible bearer-token warning** ("Anyone who gets this invite can read and write this chat and
 * use the disk…") — it renders regardless of build state. With no joined chat ([AppContainer.runtimeGraph]
 * null on the JVM) the screen shows the plain "no community yet" message rather than crashing. The
 * string+QR render path is exercised at the codec level by `QrGenerateDecodeTest` (string → BitMatrix →
 * round-trip); the live on-device draw is out of the JVM rule's reach. UI logic only.
 * Source: <https://developer.android.com/develop/ui/compose/testing>
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InviteScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The bearer-token warning is always visible — the screen's security-bearing requirement. */
    @Test
    fun invite_screen_always_shows_bearer_warning() {
        composeRule.setContent {
            InviteScreen(onBack = {}, viewModel = InviteViewModel())
        }
        composeRule.waitForIdle()

        composeRule
            .onNodeWithText("Anyone who gets this invite", substring = true)
            .assertIsDisplayed()
    }

    /** With no joined chat, the screen shows the plain "no community yet" message — never a crash. */
    @Test
    fun invite_screen_no_chat_shows_plain_message() {
        composeRule.setContent {
            InviteScreen(onBack = {}, viewModel = InviteViewModel())
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(InviteViewModel.NO_CHAT_MESSAGE).assertIsDisplayed()
    }
}
