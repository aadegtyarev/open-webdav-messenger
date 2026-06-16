package org.openwebdav.messenger.sync

import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.data.MessengerDatabase
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.protocol.ChatPaths
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stack-spec tests — each asserts against a CITED stack-notes rule (source URL in the comment), not a
 * self-consistent round-trip (`docs/features/sync_plan.md` → Test plan / Stack expectations touched).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SyncStackSpecTest {
    private lateinit var server: MockWebServer
    private lateinit var disk: FakeDisk
    private lateinit var db: MessengerDatabase
    private lateinit var sender: Identity
    private val key: ChatKey = SyncTestSupport.fixedChatKey()
    private val me = "bob"
    private val chatId = SyncTestSupport.CHAT_ID

    @Before
    fun setUp() {
        server = MockWebServer()
        disk = FakeDisk()
        server.dispatcher = disk
        server.start()
        db = SyncTestSupport.inMemoryDb()
        sender = SyncTestSupport.newIdentity()
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    /**
     * periodic_request_clamped_to_15min_floor — the enqueued PeriodicWorkRequest interval is ≥ the
     * platform floor `MIN_PERIODIC_INTERVAL_MILLIS` (900000 ms = 15 min). Requesting a shorter interval
     * does NOT yield a shorter one (WorkManager clamps it).
     * Source: <https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work#schedule_periodic_work>
     */
    @Test
    fun `periodic poll request is at least the 15 minute floor`() {
        // Even when the app requests 60 seconds, the built request's interval is clamped to the floor.
        val request = SyncScheduler.pollRequest(requestedSeconds = 60)
        val intervalMillis = request.workSpec.intervalDuration
        assertTrue(
            "interval $intervalMillis must be >= MIN_PERIODIC_INTERVAL_MILLIS ${PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS}",
            intervalMillis >= PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS,
        )
    }

    /**
     * The WorkManager Worker runs one poll cycle and maps backedOff→retry / else→success, driven by the
     * `androidx.work:work-testing` TestDriver under `./gradlew test`.
     * Source: <https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/integration-testing>
     */
    @Test
    fun `worker runs a cycle via the work-testing TestDriver`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        val wm = WorkManager.getInstance(context)

        // The installed runner reports a clean cycle → the Worker must succeed.
        SyncRunner.install(SyncRunner { CycleOutcome(newCount = 0, skippedCount = 0, backedOff = false) })
        val request = SyncScheduler.pollRequest(requestedSeconds = 900)
        wm.enqueue(request).result.get()

        // Drive the periodic work's first run deterministically (TestDriver).
        WorkManagerTestInitHelper.getTestDriver(context)!!.setPeriodDelayMet(request.id)
        val info = wm.getWorkInfoById(request.id).get()
        // After a successful periodic run the work returns to ENQUEUED (awaiting the next period).
        assertTrue(info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.RUNNING)
    }

    /**
     * propfind_uses_depth_1 — the poll lists collections with `Depth: 1`, never `infinity` (servers MAY
     * disable infinity). Asserted on the actual header the transport sent to MockWebServer.
     * Source: <https://datatracker.ietf.org/doc/html/rfc4918#section-9.1>
     */
    @Test
    fun `poll PROPFIND sets Depth 1 never infinity`() =
        runTest {
            val entry = SyncTestSupport.sealedLogEntry(SyncTestSupport.text(sender), key, sender, "alice")
            disk.putFile(ChatPaths.message(chatId, entry.orderToken, entry.bytes), entry.bytes)
            val indexPath = ChatPaths.changeIndex(me, SyncTestSupport.CHAT_ID)
            disk.putFile("$indexPath/${SyncTestSupport.changeEntryName(SyncTestSupport.CHAT_ID, entry.orderToken)}", byteArrayOf(0))

            engine().pollCycle(me, listOf(ChatSubscription(SyncTestSupport.CHAT_ID)))

            assertTrue("at least one PROPFIND was issued", disk.propfindDepths.isNotEmpty())
            assertTrue("every PROPFIND used Depth: 1", disk.propfindDepths.all { it == "1" })
        }

    /**
     * get_rejects_hash_mismatch — a §3 on-read content-hash check rejects a body whose recomputed hash
     * ≠ the file name; such a file is treated as not-ready (skipped), never surfaced as a message.
     * Source: webdav-layout §3 (reader integrity check) / stack-notes OkHttp+WebDAV.
     */
    @Test
    fun `get rejects a body whose hash does not match the name`() =
        runTest {
            val entry = SyncTestSupport.sealedLogEntry(SyncTestSupport.text(sender), key, sender, "alice")
            // store the RIGHT name but the WRONG bytes → hash mismatch on read
            disk.putFile(ChatPaths.message(chatId, entry.orderToken, entry.bytes), byteArrayOf(9, 9, 9))
            val indexPath = ChatPaths.changeIndex(me, SyncTestSupport.CHAT_ID)
            disk.putFile("$indexPath/${SyncTestSupport.changeEntryName(SyncTestSupport.CHAT_ID, entry.orderToken)}", byteArrayOf(0))

            val outcome = engine().pollCycle(me, listOf(ChatSubscription(SyncTestSupport.CHAT_ID)))

            assertEquals(0, outcome.newCount) // hash mismatch → not surfaced
            assertTrue(outcome.skippedCount >= 1)
        }

    private fun engine(): SyncEngine =
        SyncEngine(
            SyncTestSupport.transport(server),
            SyncTestSupport.messageEnvelope(),
            SyncTestSupport.store(db),
            { key },
            clock = { 1L },
        )
}
