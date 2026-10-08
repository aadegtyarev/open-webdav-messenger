package org.openwebdav.messenger.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class LegacyHistoryRepairTest {
    @Test
    fun startup_repair_is_safe_for_single_and_ambiguous_owners() =
        runBlocking {
            val database =
                Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    MessengerDatabase::class.java,
                ).allowMainThreadQueries().build()
            try {
                val messages = database.messageDao()
                val cursors = database.syncCursorDao()
                val legacy = MessengerDatabase.LEGACY_UNSCOPED_COMMUNITY_ID
                messages.insertIgnore(message(legacy, "move", "history"))
                messages.insertIgnore(message(legacy, "collision", "legacy"))
                messages.insertIgnore(message("community-a", "collision", "owned"))
                messages.insertIgnore(message(legacy, "outbox", "retry").copy(outboxEnvelope = byteArrayOf(1)))
                cursors.upsert(SyncCursorEntity(legacy, "general", "1"))
                cursors.upsert(SyncCursorEntity("community-a", "general", "2"))

                val sql = database.openHelper.writableDatabase
                LegacyHistoryRepair.repair(sql, listOf("community-a"))
                LegacyHistoryRepair.repair(sql, listOf("community-a"))

                assertEquals(1, messages.count("community-a", "move"))
                assertEquals(1, messages.count(legacy, "collision"))
                assertEquals(1, messages.count("community-a", "collision"))
                assertEquals(1, messages.count(legacy, "outbox"))
                assertTrue(messages.pendingOutgoing("community-a").isEmpty())
                assertEquals("2", cursors.cursorFor("community-a", "general")?.orderToken)

                messages.insertIgnore(message(legacy, "ambiguous", "hidden"))
                LegacyHistoryRepair.repair(sql, listOf("community-a", "community-b"))
                assertEquals(1, messages.count(legacy, "ambiguous"))
                assertEquals(0, messages.count("community-a", "ambiguous"))
            } finally {
                database.close()
            }
        }

    private fun message(
        communityId: String,
        messageId: String,
        body: String,
    ) = MessageEntity(
        communityId = communityId,
        messageId = messageId,
        chatId = "general",
        orderToken = messageId,
        senderSignPub = "sender",
        kind = MessageEntity.KIND_TEXT,
        body = body,
        replyTo = null,
        targetId = null,
        reactionIndex = null,
        sendTimestampMillis = null,
        receivedAtMillis = 1,
    )
}
