package org.openwebdav.messenger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.sqlcipher.database.SupportFactory
import org.openwebdav.messenger.keystore.HistoryKeyStore
import java.io.File
import net.sqlcipher.database.SQLiteDatabase as SqlcipherDatabase

/**
 * The app-private Room database holding local message history and per-chat sync cursors
 * (`docs/features/sync_plan.md` → Local history; `docs/protocol/webdav-layout.md` §9.3).
 *
 * `exportSchema = true` and the generated JSON is checked into `app/schemas/` so migrations are
 * reviewable in VCS and the `connectedAndroidTest` `MigrationTestHelper` can validate them
 * (stack-notes Room migrations). A schema bump requires a `Migration` — never a silent destructive
 * fallback (the migration list is empty at v1; v2+ adds entries here).
 *
 * Built without `allowMainThreadQueries()` (stack-notes Room: no main-thread DB access) — every DAO
 * is `suspend`/`Flow`/`PagingSource`.
 *
 * **At-rest encryption** (decision "Local history encryption", SC17): the database is encrypted via
 * SQLCipher with an AES-256 key Keystore-wrapped by [HistoryKeyStore]. On first launch after the
 * upgrade, an existing unencrypted database is migrated transparently via
 * `ATTACH DATABASE` + `sqlcipher_export`.
 */
@Database(
    entities = [MessageEntity::class, SyncCursorEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class MessengerDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    abstract fun syncCursorDao(): SyncCursorDao

    companion object {
        /** On-device database file name. */
        const val DB_NAME = "owdm-messages.db"

        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE messages ADD COLUMN sendStatus TEXT NOT NULL DEFAULT 'SENT'",
                    )
                }
            }

        @Volatile
        private var instance: MessengerDatabase? = null

        /** The process-wide singleton (one RoomDatabase per process — Room guidance). */
        fun get(context: Context): MessengerDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        @Suppress("TooGenericExceptionCaught") // migration detection handles SQLCipher/open failures gracefully
        private fun build(context: Context): MessengerDatabase {
            // SQLCipher native library must be loaded before any SQLiteDatabase call.
            SqlcipherDatabase.loadLibs(context)

            // Migrate an existing unencrypted database to encrypted before Room opens it.
            migrateUnencryptedIfNeeded(context)

            val historyKeyStore = HistoryKeyStore(context)
            val key = historyKeyStore.getOrCreateKey()
            try {
                val factory = SupportFactory(key)
                return Room.databaseBuilder(context, MessengerDatabase::class.java, DB_NAME)
                    .openHelperFactory(factory)
                    .addMigrations(MIGRATION_1_2)
                    // No allowMainThreadQueries() — DAOs are suspend/Flow (stack-notes Room).
                    // No fallbackToDestructiveMigration() — a schema bump must ship a Migration so local
                    // history is never silently dropped (stack-notes Room migrations).
                    .build()
            } finally {
                key.fill(0)
            }
        }

        /**
         * If [DB_NAME] exists as an unencrypted database (pre-SQLCipher), migrate it to an encrypted
         * database using SQLCipher's `ATTACH DATABASE` + `sqlcipher_export`. The migration creates an
         * encrypted copy in a temp file, then atomically replaces the original.
         *
         * Detection: open the existing file with a `null` password via SQLCipher. If the open succeeds,
         * the database is unencrypted (SQLCipher in plaintext mode reads standard SQLite files). If it
         * throws (encrypted pages detected), the database is already encrypted and no migration is needed.
         */
        private fun migrateUnencryptedIfNeeded(context: Context) {
            val dbFile = context.getDatabasePath(DB_NAME)
            if (!dbFile.exists()) return

            try {
                // Open with null password — succeeds only if the DB is unencrypted.
                val probeDb =
                    SqlcipherDatabase.openDatabase(
                        dbFile.absolutePath,
                        null as String?,
                        null,
                        SqlcipherDatabase.OPEN_READONLY,
                    )
                probeDb.close()
            } catch (_: Exception) {
                // Already encrypted (or corrupt — let SupportFactory handle it).
                return
            }

            // The database is unencrypted — migrate to encrypted.
            val historyKeyStore = HistoryKeyStore(context)
            val key = historyKeyStore.getOrCreateKey()
            try {
                val tmpFile = File(dbFile.parent, "$DB_NAME.migration_tmp")
                tmpFile.delete()

                val encryptedDb =
                    SqlcipherDatabase.openOrCreateDatabase(tmpFile.absolutePath, key, null, null)
                try {
                    encryptedDb.rawExecSQL(
                        "ATTACH DATABASE '${dbFile.absolutePath}' AS plaintext KEY ''",
                    )
                    encryptedDb.rawExecSQL("SELECT sqlcipher_export('plaintext')")
                    encryptedDb.rawExecSQL("DETACH DATABASE plaintext")
                } finally {
                    encryptedDb.close()
                }

                // Replace the unencrypted file with the encrypted copy.
                // If the rename fails (e.g. cross-filesystem), fall back to copy-then-delete.
                dbFile.delete()
                if (!tmpFile.renameTo(dbFile)) {
                    tmpFile.copyTo(dbFile, overwrite = true)
                    tmpFile.delete()
                }
            } finally {
                key.fill(0)
            }
        }
    }
}
