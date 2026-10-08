package org.openwebdav.messenger.ui.chatlist

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.app.AppContainer
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

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

    @Test
    fun failed_group_open_keeps_general_available_in_the_chat_list() {
        val attempts = CopyOnWriteArrayList<String>()
        val feedOpens = AtomicInteger()
        val chats =
            listOf(
                AppContainer.UnifiedChat("group-id", "Болталка", "group", "community-a", "A"),
                AppContainer.UnifiedChat("general-id", "General", "general", "community-a", "A"),
            )
        composeRule.setContent {
            UnifiedChatListScreen(
                onCreateCommunity = {},
                onJoin = {},
                onOpenFeed = { feedOpens.incrementAndGet() },
                onSettings = {},
                chatItems = chats,
                openChat = { chat ->
                    attempts += chat.chatId
                    chat.kind == "general"
                },
                observeUnreadCount = { _, _ -> flowOf(0) },
            )
        }

        composeRule.onNodeWithContentDescription("A · Болталка").performClick()
        composeRule.waitUntil(5_000) { attempts.size == 1 }
        composeRule.waitForIdle()
        assertEquals(listOf("group-id"), attempts)
        assertEquals(0, feedOpens.get())
    }

    @Test
    fun general_chat_opens_feed() {
        val feedOpens = AtomicInteger()
        composeRule.setContent {
            UnifiedChatListScreen(
                onCreateCommunity = {},
                onJoin = {},
                onOpenFeed = { feedOpens.incrementAndGet() },
                onSettings = {},
                chatItems = listOf(AppContainer.UnifiedChat("general-id", "General", "general", "community-a", "A")),
                openChat = { true },
                observeUnreadCount = { _, _ -> flowOf(0) },
            )
        }
        composeRule.onNodeWithContentDescription("A · General").performClick()
        composeRule.waitUntil(5_000) { feedOpens.get() == 1 }
        assertEquals(1, feedOpens.get())
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
