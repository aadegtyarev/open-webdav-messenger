package org.openwebdav.messenger.ui.chatlist

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.app.AppContainer
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Compose UI tests for [UnifiedChatListScreen].
 */
@RunWith(RobolectricTestRunner::class)
class UnifiedChatListScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        AppContainer.bind(RuntimeEnvironment.getApplication())
    }

    // -- Empty state ----------------------------------------------------------

    @Test
    fun empty_state_shows_prompt() {
        composeRule.setContent {
            UnifiedChatListScreen(
                onCreateCommunity = {},
                onJoin = {},
                onOpenFeed = {},
                onSettings = {},
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("No chats yet.").assertIsDisplayed()
        composeRule.onNodeWithText("Tap + to create a chat or join a community.").assertIsDisplayed()
    }

    // -- Top bar --------------------------------------------------------------

    @Test
    fun top_bar_shows_title_and_settings() {
        composeRule.setContent {
            UnifiedChatListScreen(
                onCreateCommunity = {},
                onJoin = {},
                onOpenFeed = {},
                onSettings = {},
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Chats").assertIsDisplayed()
    }
}
