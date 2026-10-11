package org.openwebdav.messenger.ui.chatlist

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.app.AppContainer
import org.openwebdav.messenger.app.ChatDeleteCapability
import org.openwebdav.messenger.app.ChatListDeletionResult
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
    fun locally_hidden_dm_is_not_listed_but_other_chat_types_remain_visible() {
        val chats =
            listOf(
                AppContainer.UnifiedChat("dm-id", "Peer", "dm", "community-a", "A"),
                AppContainer.UnifiedChat("general-id", "General", "general", "community-a", "A"),
            )
        composeRule.setContent {
            UnifiedChatListScreen(
                onCreateCommunity = {},
                onJoin = {},
                onOpenFeed = {},
                onSettings = {},
                chatItems = chats,
                deletedChatKeys = setOf("community-a" to "dm-id"),
                observeUnreadCount = { _, _ -> flowOf(0) },
            )
        }
        composeRule.onNodeWithContentDescription("A · Peer").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("A · General").assertIsDisplayed()
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
                openChat = { chat, _ ->
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
    fun stale_success_cannot_navigate_or_cancel_the_latest_open() {
        val opens = CopyOnWriteArrayList<CompletableDeferred<Boolean>>()
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
                openChat = { _, _ ->
                    val deferred = CompletableDeferred<Boolean>()
                    opens.add(deferred)
                    deferred.await()
                },
                observeUnreadCount = { _, _ -> flowOf(0) },
            )
        }

        composeRule.onNodeWithContentDescription("A · Болталка").performClick()
        composeRule.waitUntil(5_000) { opens.size == 1 }
        composeRule.onNodeWithContentDescription("A · General").performClick()
        composeRule.waitUntil(5_000) { opens.size == 2 }
        opens[0].complete(true)
        composeRule.waitForIdle()
        assertEquals(0, feedOpens.get())
        opens[1].complete(true)
        composeRule.waitUntil(5_000) { feedOpens.get() == 1 }
    }

    @Test
    fun stale_failure_shows_no_error_while_latest_success_navigates() {
        val opens = CopyOnWriteArrayList<CompletableDeferred<Boolean>>()
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
                openChat = { _, _ ->
                    val deferred = CompletableDeferred<Boolean>()
                    opens.add(deferred)
                    deferred.await()
                },
                observeUnreadCount = { _, _ -> flowOf(0) },
            )
        }

        composeRule.onNodeWithContentDescription("A · Болталка").performClick()
        composeRule.waitUntil(5_000) { opens.size == 1 }
        composeRule.onNodeWithContentDescription("A · General").performClick()
        composeRule.waitUntil(5_000) { opens.size == 2 }
        opens[0].completeExceptionally(IllegalStateException("stale open failed"))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Could not open this chat. Please try again.").assertDoesNotExist()
        opens[1].complete(true)
        composeRule.waitUntil(5_000) { feedOpens.get() == 1 }
    }

    @Test
    fun stale_cancellation_does_not_show_error_or_cancel_latest_open() {
        val opens = CopyOnWriteArrayList<CompletableDeferred<Boolean>>()
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
                openChat = { _, _ ->
                    val deferred = CompletableDeferred<Boolean>()
                    opens.add(deferred)
                    deferred.await()
                },
                observeUnreadCount = { _, _ -> flowOf(0) },
            )
        }
        composeRule.onNodeWithContentDescription("A · Болталка").performClick()
        composeRule.waitUntil(5_000) { opens.size == 1 }
        composeRule.onNodeWithContentDescription("A · General").performClick()
        composeRule.waitUntil(5_000) { opens.size == 2 }
        opens[0].completeExceptionally(CancellationException("stale request cancelled"))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Could not open this chat. Please try again.").assertDoesNotExist()
        opens[1].complete(true)
        composeRule.waitUntil(5_000) { feedOpens.get() == 1 }
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
                openChat = { _, _ -> true },
                observeUnreadCount = { _, _ -> flowOf(0) },
            )
        }
        composeRule.onNodeWithContentDescription("A · General").performClick()
        composeRule.waitUntil(5_000) { feedOpens.get() == 1 }
        assertEquals(1, feedOpens.get())
    }

    @Test
    fun list_overflow_hides_unauthorized_remote_delete_but_keeps_local_dm_action() {
        val chats =
            listOf(
                AppContainer.UnifiedChat("group-id", "Group", "group", "community-a", "A"),
                AppContainer.UnifiedChat("dm-id", "Peer", "dm", "community-a", "A"),
                AppContainer.UnifiedChat("general-id", "General", "general", "community-a", "A"),
            )
        composeRule.setContent {
            UnifiedChatListScreen(
                onCreateCommunity = {},
                onJoin = {},
                onOpenFeed = {},
                onSettings = {},
                chatItems = chats,
                observeUnreadCount = { _, _ -> flowOf(0) },
                projectDeleteCapability = { _, _ -> ChatDeleteCapability.NotAuthorized },
            )
        }
        composeRule.onNodeWithContentDescription("Actions for A · Group").performClick()
        composeRule.onNodeWithText("Delete for everyone").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Actions for Peer").performClick()
        composeRule.onNodeWithText("Delete locally").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("A · General").assertIsDisplayed()
    }

    @Test
    fun authorized_list_delete_requires_typed_confirmation_and_reports_pending_cleanup() {
        val selected = CopyOnWriteArrayList<Boolean>()
        val chat = AppContainer.UnifiedChat("group-id", "Group", "group", "community-a", "A")
        composeRule.setContent {
            UnifiedChatListScreen(
                onCreateCommunity = {},
                onJoin = {},
                onOpenFeed = {},
                onSettings = {},
                chatItems = listOf(chat),
                observeUnreadCount = { _, _ -> flowOf(0) },
                projectDeleteCapability = { _, _ -> ChatDeleteCapability.Available },
                deleteChat = { _, _, localOnly ->
                    selected += localOnly
                    ChatListDeletionResult.REMOTE_CLEANUP_PENDING
                },
            )
        }
        composeRule.onNodeWithContentDescription("Actions for A · Group").performClick()
        composeRule.onNodeWithText("Delete for everyone").performClick()
        composeRule.onNodeWithText(
            "Type “Group” to publish a permanent deletion tombstone. Remote content cleanup may remain pending.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Type the chat title").performTextInput("Group")
        composeRule.onNodeWithText("Delete for everyone").performClick()
        composeRule.waitUntil(5_000) { selected.size == 1 }
        composeRule.onNodeWithText(
            "Chat is deleted. Remote cleanup remains pending and will retry; governance records are retained.",
        ).assertIsDisplayed()
        assertEquals(listOf(false), selected.toList())
    }

    @Test
    fun unavailable_list_authority_can_retry_and_exposes_only_after_available() {
        val attempts = AtomicInteger()
        val chat = AppContainer.UnifiedChat("group-id", "Group", "group", "community-a", "A")
        composeRule.setContent {
            UnifiedChatListScreen(
                onCreateCommunity = {},
                onJoin = {},
                onOpenFeed = {},
                onSettings = {},
                chatItems = listOf(chat),
                observeUnreadCount = { _, _ -> flowOf(0) },
                projectDeleteCapability = { _, _ ->
                    if (attempts.incrementAndGet() == 1) ChatDeleteCapability.Unavailable else ChatDeleteCapability.Available
                },
            )
        }
        composeRule.onNodeWithContentDescription("Actions for A · Group").performClick()
        composeRule.onNodeWithText("Delete authority unavailable. Tap to retry.").performClick()
        composeRule.waitUntil(5_000) { attempts.get() >= 2 }
        composeRule.onNodeWithText("Delete for everyone").assertIsDisplayed()
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
