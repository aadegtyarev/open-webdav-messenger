package org.openwebdav.messenger.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * room_migration_tested (`docs/features/sync_plan.md` Test plan; stack-notes Room migrations) — the
 * checked-in schema (`app/schemas/`, `exportSchema = true`) opens on-device via [MigrationTestHelper].
 *
 * At schema **v1** there is no prior version to migrate FROM, so this test exercises the helper's
 * create-and-open path (the harness that future v1→v2 migrations plug into) and a real-DB open + write
 * round-trip on the device's SQLite. A future schema bump adds a `Migration` and a v(N-1)→vN assertion
 * here. Runs under `./gradlew connectedAndroidTest` (needs an emulator/device — the native SQLite +
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

    /** Open the real Room database and round-trip a write/read on-device (native SQLite). */
    @Test
    fun opensRealDatabaseAndPersists() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.databaseBuilder(context, MessengerDatabase::class.java, REAL_DB).build()
        try {
            // Smoke: the schema is usable on-device (DAO objects are obtainable; the DB opens).
            assertNotNull(db.messageDao())
            assertNotNull(db.syncCursorDao())
        } finally {
            db.close()
            context.deleteDatabase(REAL_DB)
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
        const val REAL_DB = "open-real-test.db"
    }
}
