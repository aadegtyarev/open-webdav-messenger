package org.openwebdav.messenger.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.crypto.LazySodiumCrypto
import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.message.TextMessage
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room local-history tests:
 * room_history_is_observable_and_offline, dedup idempotency, cursor advance, and the stack-spec
 * `room_dao_rejects_main_thread_access` (DAOs are suspend/Flow; the DB is built WITHOUT
 * `allowMainThreadQueries()`). Source (Room async): <https://developer.android.com/training/data-storage/room/async-queries>
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MessageStoreTest {
    private lateinit var db: MessengerDatabase
    private lateinit var store: MessageStore

    private val sodium = LazySodiumJava(SodiumJava())
    private val identity = IdentityCrypto(LazySodiumCrypto(sodium)).generateIdentity()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MessengerDatabase::class.java).build()
        store = MessageStore(db.messageDao(), db.syncCursorDao(), "default")
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun text(body: String) =
        TextMessage(
            chatId = "c1",
            sender = identity.publicIdentity(),
            replyTo = null,
            body = body,
            sendTimestampMillis = 1L,
        )

    /** room_history_is_observable_and_offline: observed via the DAO Flow, no network. */
    @Test
    fun `history is observable via the DAO flow`() =
        runTest {
            store.persist("0001~aaa", "0001", text("hello"), receivedAtMillis = 5L)
            val emitted = db.messageDao().observeChat("default", "c1").first()
            assertEquals(listOf("hello"), emitted.map { it.body })
        }

    /** Idempotent insert: the same §2 message-id persisted twice → one row, second persist returns false. */
    @Test
    fun `persist is idempotent on the message id`() =
        runTest {
            val firstInsert = store.persist("0001~aaa", "0001", text("once"), receivedAtMillis = 1L)
            val secondInsert = store.persist("0001~aaa", "0001", text("once"), receivedAtMillis = 2L)
            assertTrue(firstInsert)
            assertFalse(secondInsert) // duplicate → no new row
            assertEquals(1, store.messagesForChat("c1").size)
        }

    @Test
    fun dm_delete_suppresses_exact_replay_purges_outbox_and_reopens_only_on_new_incoming() =
        runTest {
            store.persist(
                "old-id",
                "0001",
                text("old"),
                receivedAtMillis = 1L,
                sendStatus = MessageEntity.STATUS_SENDING,
                outboxEnvelope = byteArrayOf(1, 2, 3),
                outboxRecipients = listOf("peer"),
                outboxCommunityId = "default",
            )
            store.advanceCursor("c1", "0001")

            assertEquals(DmLocalDeleteResult.Deleted, store.hideAndPurgeDm("c1"))
            assertTrue(store.isDmHidden("c1"))
            assertTrue(store.messagesForChat("c1").isEmpty())
            assertEquals("", store.cursorFor("c1"))
            assertTrue(store.pendingOutgoing("default").isEmpty())

            assertFalse(store.persist("old-id", "0001", text("old replay"), receivedAtMillis = 2L))
            assertTrue(store.isDmHidden("c1"))
            assertFalse(
                store.persist(
                    "own-retry",
                    "0002",
                    text("own retry"),
                    receivedAtMillis = 3L,
                    sendStatus = MessageEntity.STATUS_SENDING,
                ),
            )
            assertFalse(
                store.persist(
                    "own-remote-replay",
                    "0002",
                    text("own replay"),
                    receivedAtMillis = 3L,
                    peerMessage = false,
                ),
            )
            assertTrue(store.isDmHidden("c1"))

            assertTrue(store.persist("new-peer-id", "0003", text("new peer"), receivedAtMillis = 4L))
            assertFalse(store.isDmHidden("c1"))
            assertEquals(listOf("new peer"), store.messagesForChat("c1").map { it.body })
        }

    @Test
    fun durable_delete_commits_graveyard_with_atomic_purge_and_fences_late_messages() =
        runTest {
            store.persist("old-message", "0001", text("before delete"), receivedAtMillis = 1L)
            store.advanceCursor("c1", "0001")
            val graveyard =
                ChatDeletionEntity(
                    communityId = "default",
                    chatId = "c1",
                    scope = ChatDeletionEntity.SCOPE_GROUP,
                    originHash = "a".repeat(32),
                    tombstoneHash = "b".repeat(32),
                    tombstoneStoredBytes = byteArrayOf(4, 5, 6),
                    phase = ChatDeletionEntity.PHASE_LOCAL_COMMITTED,
                    updatedAtMillis = 2L,
                )

            assertEquals(ChatDeletionCommitResult.Committed, store.commitChatDeletion(graveyard))
            assertEquals(ChatDeletionCommitResult.AlreadyCommitted, store.commitChatDeletion(graveyard))
            assertTrue(store.isChatDeleted("c1"))
            assertTrue(store.messagesForChat("c1").isEmpty())
            assertEquals("", store.cursorFor("c1"))
            assertFalse(store.persist("late-message", "0002", text("stale poll"), receivedAtMillis = 3L))
            assertTrue(store.pendingOutgoing("default").isEmpty())
            assertTrue(
                store.transitionDeletionPhase(
                    "c1",
                    graveyard.tombstoneHash,
                    ChatDeletionEntity.PHASE_LOCAL_COMMITTED,
                    ChatDeletionEntity.PHASE_TOMBSTONE_PUBLISHED,
                    4L,
                ),
            )
        }

    @Test
    fun restoring_backup_without_graveyard_never_clears_local_delete_wins_state() =
        runTest {
            val deletion =
                ChatDeletionEntity(
                    "default",
                    "c1",
                    ChatDeletionEntity.SCOPE_GROUP,
                    "a".repeat(32),
                    "b".repeat(32),
                    byteArrayOf(1, 2),
                    ChatDeletionEntity.PHASE_LOCAL_COMMITTED,
                    updatedAtMillis = 1L,
                )
            store.commitChatDeletion(deletion)

            db.messageDao().replaceDmDeletionState("default", emptyList(), emptyList(), emptyList())

            assertTrue(store.isChatDeleted("c1"))
            assertEquals(deletion.tombstoneHash, store.chatDeletion("c1")?.tombstoneHash)
        }

    @Test
    fun identical_chat_history_read_state_and_cursor_are_community_scoped() =
        runTest {
            val communityA = MessageStore(db.messageDao(), db.syncCursorDao(), "community-a")
            val communityB = MessageStore(db.messageDao(), db.syncCursorDao(), "community-b")
            communityA.persist("same-id", "0005", text("A"), receivedAtMillis = 1L)
            communityB.persist("same-id", "0005", text("B"), receivedAtMillis = 2L)

            assertEquals(listOf("A"), communityA.messagesForChat("c1").map { it.body })
            assertEquals(listOf("B"), communityB.messagesForChat("c1").map { it.body })
            communityA.markMessagesReadUpTo("c1", "0005")
            assertEquals(MessageEntity.STATUS_READ, communityA.messagesForChat("c1").single().sendStatus)
            assertEquals(MessageEntity.STATUS_SENT, communityB.messagesForChat("c1").single().sendStatus)

            communityA.advanceCursor("c1", "0009")
            communityB.advanceCursor("c1", "0005")
            assertEquals("0009", communityA.cursorFor("c1"))
            assertEquals("0005", communityB.cursorFor("c1"))
        }

    /** Cursor advances forward only, never backwards (§9.3). */
    @Test
    fun `cursor advances forward only`() =
        runTest {
            store.advanceCursor("c1", "0005")
            store.advanceCursor("c1", "0003") // older — must NOT move the cursor back
            assertEquals("0005", store.cursorFor("c1"))
            store.advanceCursor("c1", "0009")
            assertEquals("0009", store.cursorFor("c1"))
        }

    /**
     * room_dao_rejects_main_thread_access — the DB is built WITHOUT allowMainThreadQueries(), the flag
     * Room consults in `assertNotMainThread()` to reject a main-thread query. We assert the configured
     * flag is off directly (it is private on RoomDatabase as `mAllowMainThreadQueries`), which is what
     * makes the suspend/Flow DAOs the only safe access path. The DAO surface is itself entirely
     * suspend/Flow/PagingSource (compile-time fact). Source: <https://developer.android.com/training/data-storage/room/async-queries>
     */
    @Test
    fun `database is built without allowMainThreadQueries`() {
        val field =
            generateSequence<Class<*>>(db.javaClass) { it.superclass }
                .mapNotNull { runCatching { it.getDeclaredField("allowMainThreadQueries") }.getOrNull() }
                .first()
        field.isAccessible = true
        assertFalse("allowMainThreadQueries() must be off (stack-notes Room)", field.getBoolean(db))
    }
}
