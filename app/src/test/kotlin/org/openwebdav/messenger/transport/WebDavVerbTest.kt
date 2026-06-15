package org.openwebdav.messenger.transport

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.openwebdav.messenger.protocol.ChatPaths
import org.openwebdav.messenger.protocol.Envelope
import org.openwebdav.messenger.protocol.MessageId
import org.openwebdav.messenger.protocol.OrderToken

/**
 * Unit tests (MockWebServer) for the WebDAV verbs / request shaping per the plan Test plan
 * and `docs/protocol/webdav-layout.md` §6.
 */
class WebDavVerbTest {
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

    // plan: propfind_lists_inbox_depth1 — request is PROPFIND + Depth: 1, entries expose ETag.
    @Test
    fun propfind_lists_inbox_depth1() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setBody(TestSupport.multistatus(inboxPath, "msg-a" to "etag-a", "msg-b" to "etag-b")),
            )
            val result = TestSupport.newTransport(server).list(inboxPath)
            val entries = (result as WebDavResult.Success).value

            val request = server.takeRequest()
            assertEquals("PROPFIND", request.method)
            assertEquals("1", request.getHeader("Depth"))
            assertEquals(setOf("msg-a", "msg-b"), entries.map { it.name }.toSet())
            assertEquals("\"etag-a\"", entries.first { it.name == "msg-a" }.etag)
        }

    // plan: put_message_uses_content_addressed_name — target path is the message-id; repeat → same path.
    @Test
    fun put_message_uses_content_addressed_name() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(201))
            server.enqueue(MockResponse().setResponseCode(201))

            val token = OrderToken.build(1_717_000_000_000L, "alice", 1)
            val framed = Envelope.write("payload".toByteArray())
            val path = ChatPaths.v1Message(inboxPath, token, framed)
            val transport = TestSupport.newTransport(server)

            transport.write(path, framed)
            transport.write(path, framed)

            val first = server.takeRequest()
            val second = server.takeRequest()
            assertEquals("PUT", first.method)
            val expectedName = MessageId.messageId(token, framed)
            assertTrue(first.path!!.endsWith("/$expectedName"))
            assertEquals(first.path, second.path)
        }

    // plan: get_then_delete_roundtrip — verbs/paths are GET then DELETE on the same resource.
    @Test
    fun get_then_delete_roundtrip() =
        runTest {
            val token = OrderToken.build(1_717_000_000_000L, "alice", 1)
            val framed = Envelope.write("payload".toByteArray())
            val name = MessageId.messageId(token, framed)
            val path = "$inboxPath/$name"

            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(framed)))
            server.enqueue(MockResponse().setResponseCode(204))

            val transport = TestSupport.newTransport(server)
            val read = transport.read(path, name)
            assertTrue(read is WebDavResult.Success && read.value is ReadResult.Ready)
            transport.delete(path)

            val get = server.takeRequest()
            val del = server.takeRequest()
            assertEquals("GET", get.method)
            assertEquals("DELETE", del.method)
            assertEquals(get.path, del.path)
        }

    // review finding 2: a non-loopback http:// baseUrl is rejected (CleartextRejected) before any
    // call — the Basic-auth app-password must never cross an unencrypted network hop.
    @Test
    fun http_base_url_is_rejected_as_cleartext() =
        runTest {
            val config =
                ConnectionConfig(
                    baseUrl = "http://webdav.example.com",
                    username = "user",
                    appPassword = "app-password",
                    chatRoot = "chat-root",
                )
            val transport = WebDavTransport(config, OkHttpClient(), delayer = RecordingDelayer())
            val result = transport.write("$inboxPath/msg", byteArrayOf(1))
            assertTrue(result is WebDavResult.CleartextRejected)
            // No request reached the (unused) MockWebServer.
            assertEquals(0, server.requestCount)
        }

    // review finding 2: an https:// baseUrl is accepted (passes the cleartext gate; the call then
    // proceeds). MockWebServer is loopback http, which the gate also permits (never leaves device).
    @Test
    fun https_base_url_passes_the_cleartext_gate() =
        runTest {
            assertNull(PathSafety.requireHttps("https://webdav.yandex.com"))
            // Loopback http (the test harness) is permitted; a normal verb succeeds.
            server.enqueue(MockResponse().setResponseCode(201))
            val result = TestSupport.newTransport(server).write("$inboxPath/msg", byteArrayOf(1))
            assertTrue(result is WebDavResult.Success)
        }

    // review finding 3: a path containing a ../ dot-segment is rejected (InvalidPath), not resolved
    // by toHttpUrl()'s silent normalization that would escape the credential-scoped chat-root.
    @Test
    fun path_traversal_is_rejected_not_resolved() =
        runTest {
            val transport = TestSupport.newTransport(server)
            val result = transport.read("inbox/../../etc/passwd", "name~hash")
            assertTrue(result is WebDavResult.InvalidPath)
            assertEquals(0, server.requestCount) // fail-closed: no request issued
        }

    // review finding 4: ConnectionConfig.toString() redacts the app-password so it never leaks via
    // an accidental log; the other fields still print.
    @Test
    fun connection_config_tostring_redacts_password() {
        val secret = "super-secret-app-password"
        val rendered =
            ConnectionConfig(
                baseUrl = "https://webdav.yandex.com",
                username = "user",
                appPassword = secret,
                chatRoot = "chat-root",
            ).toString()
        assertTrue(secret !in rendered)
        assertTrue("appPassword=***" in rendered)
        assertTrue("user" in rendered) // non-secret fields still visible
    }

    // plan: mkcol_creates_collection — MKCOL issued; existing-collection (405/301) handled idempotently.
    @Test
    fun mkcol_creates_collection_and_existing_is_idempotent() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(201))
            server.enqueue(MockResponse().setResponseCode(405))

            val transport = TestSupport.newTransport(server)
            val created = transport.ensureCollection(ChatPaths.INBOX)
            val existing = transport.ensureCollection(ChatPaths.INBOX)

            assertEquals("MKCOL", server.takeRequest().method)
            server.takeRequest()
            assertTrue(created is WebDavResult.Success)
            assertTrue(existing is WebDavResult.Success) // 405 = already exists → success
        }
}
