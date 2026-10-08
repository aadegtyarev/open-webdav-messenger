package org.openwebdav.messenger.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.directory.DirectoryEntry
import org.openwebdav.messenger.directory.DirectoryReadResult
import org.openwebdav.messenger.sync.SyncTestSupport
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class RosterCacheLockOrderTest {
    private val server = MockWebServer().apply { start() }
    private val db = SyncTestSupport.inMemoryDb()
    private val graph by lazy { AppTestSupport.recipientRosterTestGraph(server, db) }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    @Test
    fun general_commit_and_group_refresh_complete_without_lock_inversion() {
        val coordinator = RosterCommitCoordinator()
        val requests = ChatOpenRequestCoordinator(coordinator)
        val selection = RuntimeSelectionGuard(coordinator)
        val token = requests.begin()
        val revision = selection.current()
        val persistence = BlockingRosterCachePersistence()
        val cache = VerifiedRosterCache(persistence, coordinator)
        val entry = DirectoryEntry("Peer", ByteArray(32) { 1 }, ByteArray(32) { 2 })
        val roster = CachedVerifiedRoster("community", "chat", ByteArray(32), listOf(entry))
        val generalApplied = AtomicBoolean()
        val general =
            FutureTask {
                cache.commit(cache.generation(), roster, { requests.isCurrent(token) && selection.isCurrent(revision) }) {
                    requests.runIfCurrent(token) {
                        selection.runIfCurrent(revision) {
                            generalApplied.set(true)
                            true
                        }
                    }
                }
            }
        Thread(general).start()
        assertTrue(persistence.writeStarted.await(2, SECONDS))

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val groupCommitStarted = CountDownLatch(1)
        val groupFinished = CountDownLatch(1)
        val groupFailure = AtomicReference<Throwable?>()
        val enricher =
            RecipientRosterEnricher(
                scope,
                graph,
                { update ->
                    requests.runIfCurrent(token) {
                        selection.runIfCurrent(revision) {
                            update()
                            true
                        }
                    }
                },
                { DirectoryReadResult(listOf(entry), 0) },
                commitVerified = { result, isCurrent, apply ->
                    groupCommitStarted.countDown()
                    cache.commit(cache.generation(), roster.copy(entries = result.entries), isCurrent, apply)
                },
            )
        enricher.start().invokeOnCompletion { failure ->
            groupFailure.set(failure)
            groupFinished.countDown()
        }
        assertTrue(groupCommitStarted.await(2, SECONDS))
        persistence.releaseWrite.countDown()
        assertTrue(general.get(2, SECONDS))
        assertTrue(groupFinished.await(2, SECONDS))
        assertTrue(groupFailure.get() == null)
        assertTrue(generalApplied.get())
        scope.cancel()
    }
}
