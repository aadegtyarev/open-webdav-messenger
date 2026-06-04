package org.openwebdav.messenger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * DAO for the per-chat sync cursor (`docs/protocol/webdav-layout.md` §9.3). All access is `suspend`
 * (stack-notes Room: no main-thread access).
 *
 * The cursor is upserted (`OnConflictStrategy.REPLACE` on the chat-id primary key) only AFTER the
 * entries up to the new coordinate were fetched-and-persisted — the cursor-advance invariant lives in
 * the sync layer; this DAO is the durable store it advances (§9.3 / arch note §32).
 */
@Dao
interface SyncCursorDao {
    /** Read the stored cursor for [chatId], or `null` if none has been recorded yet. */
    @Query("SELECT * FROM sync_cursors WHERE chatId = :chatId")
    suspend fun cursorFor(chatId: String): SyncCursorEntity?

    /** Upsert the cursor for a chat (advance the high-water mark, §9.3). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cursor: SyncCursorEntity)
}
