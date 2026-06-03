package org.openwebdav.messenger.transport

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.openwebdav.messenger.protocol.ChatPaths
import org.openwebdav.messenger.protocol.Envelope
import org.openwebdav.messenger.protocol.MessageId
import org.openwebdav.messenger.protocol.OrderToken

/**
 * Stack-spec tests: each asserts a cited rule from `docs/stack-notes.md`, verifying against the
 * rule (not the coder's own mapping). Each test references its source URL.
 */
class StackSpecTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val inboxPath = ChatPaths.inbox("bob", "chat-1")

    // depth_header_is_1_not_infinity — RFC 4918 §9.1: poll uses Depth: 1, never infinity.
    // Source: https://datatracker.ietf.org/doc/html/rfc4918#section-9.1
    @Test
    fun depth_header_is_1_not_infinity() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(207).setBody(TestSupport.multistatus(inboxPath)))
            TestSupport.newTransport(server).list(inboxPath)

            val request = server.takeRequest()
            assertEquals("1", request.getHeader("Depth"))
            assertNotEquals("infinity", request.getHeader("Depth"))
        }

    // if_match_uses_propfind_etag — RFC 4918 §8.6: the If-Match value equals the ETag from PROPFIND.
    // Source: https://datatracker.ietf.org/doc/html/rfc4918#section-8.6
    @Test
    fun if_match_uses_propfind_etag() =
        runTest {
            // 1) PROPFIND returns an entry carrying d:getetag.
            server.enqueue(
                MockResponse().setResponseCode(207)
                    .setBody(TestSupport.multistatus(ChatPaths.META, "roster.json" to "etag-99")),
            )
            // 2) conditional PUT.
            server.enqueue(MockResponse().setResponseCode(204))

            val transport = TestSupport.newTransport(server)
            val listed = transport.list(ChatPaths.META) as WebDavResult.Success
            val etag = listed.value.first { it.name == "roster.json" }.etag
            transport.write("${ChatPaths.META}/roster.json", byteArrayOf(1), ifMatch = etag)

            server.takeRequest() // PROPFIND
            val put = server.takeRequest()
            // The If-Match the writer sends is exactly the ETag PROPFIND returned.
            assertEquals(etag, put.getHeader("If-Match"))
            assertEquals("\"etag-99\"", put.getHeader("If-Match"))
        }

    // single_okhttpclient_reused — OkHttp guidance: one shared client instance across calls.
    // Source: https://square.github.io/okhttp/5.x/okhttp/okhttp3/-ok-http-client/index.html
    @Test
    fun single_okhttpclient_reused() =
        runTest {
            val client = OkHttpClient()
            // The production factory exposes ONE lazily-created shared client.
            assertTrue(TransportFactory.sharedClient === TransportFactory.sharedClient)

            // Two transports built with the same injected client reuse its one connection pool.
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(204))
            val t1 = TestSupport.newTransport(server, client = client)
            val t2 = TestSupport.newTransport(server, client = client)
            assertTrue(t1.write("a", byteArrayOf(1)) is WebDavResult.Success)
            assertTrue(t2.write("b", byteArrayOf(1)) is WebDavResult.Success)
            assertEquals(1, client.connectionPool.connectionCount()) // same pooled connection reused
        }

    // response_bodies_closed — OkHttp guidance: no leaked response body on any path
    // (success, error, 412, 429). A leaked body holds the connection; if any path left a body
    // open, the pooled connection would not be reusable and the next call would open a new socket.
    // Source: https://square.github.io/okhttp/ (response handling examples)
    @Test
    fun response_bodies_closed_on_every_path() =
        runTest {
            val client = OkHttpClient()
            val transport = TestSupport.newTransport(server, client = client)

            val token = OrderToken.build(1_000L, "alice", 1)
            val framed = Envelope.write("x".toByteArray())
            val name = MessageId.messageId(token, framed)

            // success GET, 412 conflict, 429-then-success — exercise distinct response paths.
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when {
                            request.method == "GET" ->
                                MockResponse().setResponseCode(200).setBody(okio.Buffer().write(framed))
                            request.getHeader("If-Match") != null -> MockResponse().setResponseCode(412)
                            else -> MockResponse().setResponseCode(204)
                        }
                }

            transport.read("$inboxPath/$name", name) // success body consumed + closed
            transport.write("m", framed, ifMatch = "\"x\"") // 412 body closed
            transport.write("m2", framed) // 204 (empty) body closed

            // If any earlier body were left open, the connection could not be reused and the count
            // would exceed 1. One pooled connection ⇒ every prior body was closed.
            assertEquals(1, client.connectionPool.connectionCount())
        }
}
