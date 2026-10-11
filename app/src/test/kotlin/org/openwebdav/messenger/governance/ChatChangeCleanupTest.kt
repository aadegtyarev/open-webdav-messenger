package org.openwebdav.messenger.governance

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.protocol.ChangeEntry
import org.openwebdav.messenger.transport.TestSupport

class ChatChangeCleanupTest {
    @Test
    fun deletesOnlyChangeEntriesForTargetChat() {
        val target = "target-chat"
        val other = "other-chat"
        val order = "a".repeat(29)
        val targetEntry = ChangeEntry.name(target, order)
        val otherEntry = ChangeEntry.name(other, order)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(207).setBody(TestSupport.multistatus("changes", "idx" to "one")))
            server.enqueue(
                MockResponse().setResponseCode(207).setBody(
                    TestSupport.multistatus("changes/idx", targetEntry to "two", otherEntry to "three"),
                ),
            )
            server.enqueue(MockResponse().setResponseCode(204))
            val result =
                kotlinx.coroutines.runBlocking {
                    ChatChangeCleanup(TestSupport.newTransport(server)).clean(target)
                }
            assertEquals(1, result.deletedObjects)
            assertTrue(result.pendingReasons.isEmpty())
            val requests = listOf(server.takeRequest(), server.takeRequest(), server.takeRequest())
            assertEquals("PROPFIND", requests[0].method)
            assertEquals("PROPFIND", requests[1].method)
            assertEquals("DELETE", requests[2].method)
            assertTrue(requests[2].path!!.endsWith(targetEntry))
        }
    }
}
