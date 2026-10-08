package org.openwebdav.messenger.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.directory.DirectoryEntry
import org.openwebdav.messenger.directory.DirectoryReadResult
import org.openwebdav.messenger.protocol.Hex
import org.openwebdav.messenger.sync.SyncTestSupport
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class RecipientRosterEnricherTest {
    private val server = MockWebServer().apply { start() }
    private val db = SyncTestSupport.inMemoryDb()
    private val graph by lazy { AppTestSupport.recipientRosterTestGraph(server, db) }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    @Test
    fun local_room_feed_remains_available_while_roster_read_is_suspended() =
        runTest {
            val directory = CompletableDeferred<DirectoryReadResult>()
            val task = enricher(this) { directory.await() }.start()
            runCurrent()

            assertEquals(RecipientReadiness.Loading, graph.recipientSnapshot())
            assertTrue(graph.store.observeChat(graph.chatId).first().isEmpty())
            directory.complete(DirectoryReadResult(listOf(entry("peer")), 0))
            task.join()
            val peerId = Hex.encode("peer".toByteArray().copyOf(32))
            val expected = RecipientReadiness.Ready(listOf(graph.senderIdentifier, peerId))
            assertEquals(expected, graph.recipientSnapshot())
            assertEquals("peer", graph.memberNames[peerId])
        }

    @Test
    fun cached_ready_roster_survives_refresh_failure_silently() =
        runTest {
            val cached = RecipientReadiness.Ready(listOf(graph.senderIdentifier, "cached-peer"))
            graph.updateRecipientReadiness(cached)
            enricher(this, preserveReadyOnFailure = true) { error("offline") }.start().join()
            assertEquals(cached, graph.recipientSnapshot())
        }

    @Test
    fun failed_and_stale_reads_never_publish_ready_recipients() =
        runTest {
            enricher(this) { error("offline") }.start().join()
            assertTrue(graph.recipientSnapshot() is RecipientReadiness.Unavailable)
            var current = true
            val readStarted = CompletableDeferred<Unit>()
            val releaseRead = CompletableDeferred<DirectoryReadResult>()
            val stale =
                enricher(this, current = { current }) {
                    readStarted.complete(Unit)
                    releaseRead.await()
                }.start()
            readStarted.await()
            current = false
            releaseRead.complete(DirectoryReadResult(listOf(entry("stale")), 0))
            stale.join()
            assertTrue(graph.recipientSnapshot() is RecipientReadiness.Unavailable)
        }

    private fun enricher(
        scope: CoroutineScope,
        current: () -> Boolean = { true },
        preserveReadyOnFailure: Boolean = false,
        read: suspend () -> DirectoryReadResult,
    ): RecipientRosterEnricher =
        RecipientRosterEnricher(
            scope,
            graph,
            { update ->
                if (current()) {
                    update()
                    true
                } else {
                    false
                }
            },
            read,
            preserveReadyOnFailure = preserveReadyOnFailure,
        )

    private fun entry(seed: String) = DirectoryEntry(seed, seed.toByteArray().copyOf(32), ByteArray(32))
}
