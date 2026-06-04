package org.openwebdav.messenger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
 */
@Database(
    entities = [MessageEntity::class, SyncCursorEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class MessengerDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    abstract fun syncCursorDao(): SyncCursorDao

    companion object {
        /** On-device database file name. */
        const val DB_NAME = "owdm-messages.db"

        @Volatile
        private var instance: MessengerDatabase? = null

        /** The process-wide singleton (one RoomDatabase per process — Room guidance). */
        fun get(context: Context): MessengerDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): MessengerDatabase =
            Room.databaseBuilder(context, MessengerDatabase::class.java, DB_NAME)
                // No allowMainThreadQueries() — DAOs are suspend/Flow (stack-notes Room).
                // No fallbackToDestructiveMigration() — a schema bump must ship a Migration so local
                // history is never silently dropped (stack-notes Room migrations).
                .build()
    }
}
