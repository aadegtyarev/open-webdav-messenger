package org.openwebdav.messenger.governance

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.transport.TestSupport
import org.openwebdav.messenger.transport.WebDavResult
import java.util.concurrent.atomic.AtomicInteger

class CommunityGovernanceBoundedTransportTest {
    @Test
    fun bounded_raw_get_rejects_chunked_oversize_and_closes_the_response() =
        runTest {
            val server = MockWebServer()
            val requestCount = AtomicInteger()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        if (requestCount.incrementAndGet() == 1) {
                            MockResponse().setChunkedBody("x".repeat(2048), 64)
                        } else {
                            MockResponse().setBody("ok")
                        }
                }
            server.start()
            try {
                val transport = TestSupport.newTransport(server)
                val tooLarge = transport.readRawBounded("governance/large", 32)
                assertTrue(tooLarge is WebDavResult.TransportError && tooLarge.message.contains("oversize"))
                val next = transport.readRawBounded("governance/next", 32)
                assertArrayEquals("ok".toByteArray(), (next as WebDavResult.Success).value)
                assertEquals(2, requestCount.get())
            } finally {
                server.shutdown()
            }
        }
}
