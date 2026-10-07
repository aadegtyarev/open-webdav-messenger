package org.openwebdav.messenger.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.sqlcipher.database.SupportFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.keystore.HistoryKeyStore

/**
 * room_migration_tested (stack-notes Room migrations) — the
 * checked-in schema (`app/schemas/`, `exportSchema = true`) opens on-device via [MigrationTestHelper].
 *
 * Populated v2/v3 rows are migrated through the latest schema to verify retained local data and the
 * fail-closed ownerless outbox policy. Tests run under `./gradlew connectedAndroidTest` (needs an
 * emulator/device — the native SQLite +
 * the exported schema assets are device-backed).
 */
@RunWith(AndroidJUnit4::class)
class MessengerDatabaseMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            MessengerDatabase::class.java,
            emptyList(),
            FrameworkSQLiteOpenHelperFactory(),
        )

    /** Create the v1 schema from the checked-in JSON — proves the export is present and openable. */
    @Test
    fun createsSchemaVersion1() {
        helper.createDatabase(TEST_DB, 1).close()
    }

    @Test
    fun migratesVersion1ThroughCommunityScopedHistorySchema() {
        helper.createDatabase(TEST_DB, 1).close()
        helper.runMigrationsAndValidate(
            TEST_DB,
            5,
            true,
            MessengerDatabase.MIGRATION_1_2,
            MessengerDatabase.MIGRATION_2_3,
            MessengerDatabase.MIGRATION_3_4,
            MessengerDatabase.MIGRATION_4_5,
        ).close()
    }

    @Test
    fun populatedVersion2HistoryAndCursorSurviveUnscopedMigration() {
        helper.createDatabase(TEST_DB, 2).apply {
            execSQL(
                "INSERT INTO messages (messageId, chatId, orderToken, senderSignPub, kind, body, " +
                    "replyTo, targetId, reactionIndex, sendTimestampMillis, receivedAtMillis, sendStatus) " +
                    "VALUES ('legacy-v2', 'same-dm', '0002', 'sender', 1, 'history', NULL, NULL, NULL, 2, 3, 'SENT')",
            )
            execSQL("INSERT INTO sync_cursors (chatId, orderToken) VALUES ('same-dm', '0002')")
            close()
        }
        helper.runMigrationsAndValidate(
            TEST_DB,
            5,
            true,
            MessengerDatabase.MIGRATION_2_3,
            MessengerDatabase.MIGRATION_3_4,
            MessengerDatabase.MIGRATION_4_5,
        ).use { migrated ->
            migrated.query("SELECT communityId, body FROM messages WHERE messageId = 'legacy-v2'").use { row ->
                assertEquals(true, row.moveToFirst())
                assertEquals(MessengerDatabase.LEGACY_UNSCOPED_COMMUNITY_ID, row.getString(0))
                assertEquals("history", row.getString(1))
            }
            migrated.query("SELECT communityId, orderToken FROM sync_cursors WHERE chatId = 'same-dm'").use { row ->
                assertEquals(true, row.moveToFirst())
                assertEquals(MessengerDatabase.LEGACY_UNSCOPED_COMMUNITY_ID, row.getString(0))
                assertEquals("0002", row.getString(1))
            }
        }
    }

    @Test
    fun populatedVersion3OwnerlessOutboxPayloadIsPreservedButFailClosed() {
        val payload = byteArrayOf(7, 8, 9)
        helper.createDatabase(TEST_DB, 3).apply {
            execSQL(
                "INSERT INTO messages (messageId, chatId, orderToken, senderSignPub, kind, body, " +
                    "replyTo, targetId, reactionIndex, sendTimestampMillis, receivedAtMillis, sendStatus, " +
                    "outboxEnvelope, outboxRecipients) VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, NULL, ?, ?, ?, ?, ?)",
                arrayOf<Any>("legacy-v3", "same-dm", "0003", "sender", 1, "old send", 3L, 4L, "SENDING", payload, "peer"),
            )
            execSQL("INSERT INTO sync_cursors (chatId, orderToken) VALUES ('same-dm', '0003')")
            close()
        }
        helper.runMigrationsAndValidate(
            TEST_DB,
            5,
            true,
            MessengerDatabase.MIGRATION_3_4,
            MessengerDatabase.MIGRATION_4_5,
        ).use { migrated ->
            migrated.query(
                "SELECT communityId, sendStatus, outboxEnvelope, outboxCommunityId FROM messages WHERE messageId = 'legacy-v3'",
            ).use { row ->
                assertEquals(true, row.moveToFirst())
                assertEquals(MessengerDatabase.LEGACY_UNSCOPED_COMMUNITY_ID, row.getString(0))
                assertEquals("FAILED", row.getString(1))
                assertEquals(payload.toList(), row.getBlob(2).toList())
                assertEquals(true, row.isNull(3))
            }
            migrated.query(
                "SELECT COUNT(*) FROM messages WHERE communityId = 'community-a' AND outboxEnvelope IS NOT NULL",
            ).use { row ->
                assertEquals(true, row.moveToFirst())
                assertEquals(0, row.getInt(0))
            }
        }
    }

    @Test
    fun populatedVersion4OwnedOutboxRetainsItsCommunityNamespace() {
        val payload = byteArrayOf(4, 5, 6)
        helper.createDatabase(TEST_DB, 4).apply {
            execSQL(
                "INSERT INTO messages (messageId, chatId, orderToken, senderSignPub, kind, body, " +
                    "replyTo, targetId, reactionIndex, sendTimestampMillis, receivedAtMillis, sendStatus, " +
                    "outboxEnvelope, outboxRecipients, outboxCommunityId) " +
                    "VALUES ('owned-v4', 'same-dm', '0004', 'sender', 1, 'retry', NULL, NULL, NULL, 4, 5, " +
                    "'FAILED', ?, 'peer', 'community-a')",
                arrayOf(payload),
            )
            close()
        }
        helper.runMigrationsAndValidate(TEST_DB, 5, true, MessengerDatabase.MIGRATION_4_5).use { migrated ->
            migrated.query(
                "SELECT communityId, outboxCommunityId, outboxEnvelope FROM messages WHERE messageId = 'owned-v4'",
            ).use { row ->
                assertEquals(true, row.moveToFirst())
                assertEquals("community-a", row.getString(0))
                assertEquals("community-a", row.getString(1))
                assertEquals(payload.toList(), row.getBlob(2).toList())
            }
        }
    }

    /** Open the real Room database (SQLCipher-encrypted) and round-trip a write/read on-device. */
    @Test
    fun opensRealDatabaseAndPersists() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val historyKeyStore = HistoryKeyStore(context)
        val key = historyKeyStore.getOrCreateKey()
        val factory = SupportFactory(key)
        val db =
            Room.databaseBuilder(context, MessengerDatabase::class.java, REAL_DB)
                .openHelperFactory(factory)
                .build()
        try {
            // Smoke: the schema is usable on-device with encryption (DAO objects are obtainable).
            assertNotNull(db.messageDao())
            assertNotNull(db.syncCursorDao())
        } finally {
            key.fill(0)
            db.close()
            context.deleteDatabase(REAL_DB)
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
        const val REAL_DB = "open-real-test.db"
    }
}
