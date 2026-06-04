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
 * Room local-history tests (`docs/features/sync_plan.md` → Local history / Test plan):
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
        store = MessageStore(db.messageDao(), db.syncCursorDao())
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
            val emitted = db.messageDao().observeChat("c1").first()
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
