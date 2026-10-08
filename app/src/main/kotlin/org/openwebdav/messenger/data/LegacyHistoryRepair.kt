package org.openwebdav.messenger.data

import androidx.sqlite.db.SupportSQLiteDatabase

internal object LegacyHistoryRepair {
    fun repair(
        db: SupportSQLiteDatabase,
        joinedCommunityIds: Collection<String>,
    ) {
        val owners =
            joinedCommunityIds
                .filter { it.isNotBlank() && it != MessengerDatabase.LEGACY_UNSCOPED_COMMUNITY_ID }
                .distinct()
        if (owners.size != 1) return
        val owner = owners.single()

        db.beginTransaction()
        try {
            db.execSQL("CREATE TEMP TABLE legacy_rows_to_move (messageId TEXT PRIMARY KEY)")
            db.execSQL(
                "INSERT INTO legacy_rows_to_move SELECT legacy.messageId FROM messages legacy " +
                    "WHERE legacy.communityId = ? AND legacy.outboxEnvelope IS NULL AND legacy.outboxCommunityId IS NULL " +
                    "AND NOT EXISTS (SELECT 1 FROM messages owned WHERE owned.communityId = ? AND owned.messageId = legacy.messageId)",
                arrayOf(MessengerDatabase.LEGACY_UNSCOPED_COMMUNITY_ID, owner),
            )
            db.execSQL(
                "INSERT INTO messages (communityId, messageId, chatId, orderToken, senderSignPub, kind, body, replyTo, " +
                    "targetId, reactionIndex, sendTimestampMillis, receivedAtMillis, sendStatus, outboxEnvelope, " +
                    "outboxRecipients, outboxCommunityId, outboxClaimToken) SELECT ?, messageId, chatId, orderToken, " +
                    "senderSignPub, kind, body, replyTo, targetId, reactionIndex, sendTimestampMillis, receivedAtMillis, " +
                    "sendStatus, outboxEnvelope, outboxRecipients, outboxCommunityId, outboxClaimToken FROM messages " +
                    "WHERE communityId = ? AND messageId IN (SELECT messageId FROM legacy_rows_to_move)",
                arrayOf(owner, MessengerDatabase.LEGACY_UNSCOPED_COMMUNITY_ID),
            )
            db.execSQL(
                "DELETE FROM messages WHERE communityId = ? AND messageId IN (SELECT messageId FROM legacy_rows_to_move)",
                arrayOf(MessengerDatabase.LEGACY_UNSCOPED_COMMUNITY_ID),
            )
            mergeCursors(db, owner)
            db.execSQL("DROP TABLE legacy_rows_to_move")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun mergeCursors(
        db: SupportSQLiteDatabase,
        owner: String,
    ) {
        db.execSQL(
            "INSERT INTO sync_cursors (communityId, chatId, orderToken) " +
                "SELECT ?, chatId, orderToken FROM sync_cursors WHERE communityId = ? " +
                "ON CONFLICT(communityId, chatId) DO UPDATE SET orderToken = " +
                "MAX(sync_cursors.orderToken, excluded.orderToken)",
            arrayOf(owner, MessengerDatabase.LEGACY_UNSCOPED_COMMUNITY_ID),
        )
        db.execSQL("DELETE FROM sync_cursors WHERE communityId = ?", arrayOf(MessengerDatabase.LEGACY_UNSCOPED_COMMUNITY_ID))
    }
}
