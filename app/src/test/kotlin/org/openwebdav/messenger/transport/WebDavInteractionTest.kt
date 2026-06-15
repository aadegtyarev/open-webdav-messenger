package org.openwebdav.messenger.transport

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
 * Interaction-scenario tests over shared disk state per the plan's Interaction scenarios and
 * `docs/protocol/webdav-layout.md` §3.
 */
class WebDavInteractionTest {
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

    // interaction: concurrent_writers_distinct_names_no_overwrite — two different message-ids to
    // one inbox produce two distinct resources; neither PUT overwrites the other.
    @Test
    fun concurrent_writers_distinct_names_no_overwrite() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(201))
            server.enqueue(MockResponse().setResponseCode(201))

            val tokenA = OrderToken.build(1_000L, "alice", 1)
            val tokenB = OrderToken.build(1_000L, "carol", 1)
            val framedA = Envelope.write("from-alice".toByteArray())
            val framedB = Envelope.write("from-carol".toByteArray())
            val pathA = ChatPaths.v1Message(inboxPath, tokenA, framedA)
            val pathB = ChatPaths.v1Message(inboxPath, tokenB, framedB)

            val transport = TestSupport.newTransport(server)
            transport.write(pathA, framedA)
            transport.write(pathB, framedB)

            val reqA = server.takeRequest()
            val reqB = server.takeRequest()
            assertNotEquals(reqA.path, reqB.path) // distinct content-addressed names
            assertTrue(reqA.path!!.endsWith(MessageId.messageId(tokenA, framedA)))
            assertTrue(reqB.path!!.endsWith(MessageId.messageId(tokenB, framedB)))
        }

    // interaction: reader_skips_incomplete_file — a GET whose body's hash does not match the name
    // is treated as "not ready" (skipped/retried), NOT surfaced as corruption.
    @Test
    fun reader_skips_incomplete_file() =
        runTest {
            val token = OrderToken.build(1_000L, "alice", 1)
            val completeBytes = Envelope.write("complete".toByteArray())
            val name = MessageId.messageId(token, completeBytes) // name commits to the complete hash
            val path = "$inboxPath/$name"

            // Server returns a truncated/partial body whose recomputed hash != name suffix.
            val truncated = completeBytes.copyOf(completeBytes.size - 3)
            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(truncated)))

            val result = TestSupport.newTransport(server).read(path, name)
            assertTrue(result is WebDavResult.Success)
            // Not-ready, not an error/corruption surface.
            assertEquals(ReadResult.NotReady, (result as WebDavResult.Success).value)
        }

    // review finding 5: RFC 4918 §9.1 — a server may split found/not-found props across propstat
    // blocks with distinct <status> lines. The parser must read etag/length from the 200 OK block,
    // never from the 404 block's empty/garbage prop.
    @Test
    fun propfind_selects_props_from_200_propstat_not_404() =
        runTest {
            val body =
                """
                <?xml version="1.0" encoding="utf-8"?>
                <d:multistatus xmlns:d="DAV:">
                  <d:response>
                    <d:href>/${TestSupport.CHAT_ROOT}/$inboxPath/</d:href>
                    <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                      <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
                  </d:response>
                  <d:response>
                    <d:href>/${TestSupport.CHAT_ROOT}/$inboxPath/msg-a</d:href>
                    <d:propstat>
                      <d:prop><d:getetag>"real-etag"</d:getetag><d:getcontentlength>42</d:getcontentlength></d:prop>
                      <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                    <d:propstat>
                      <d:prop><d:getetag></d:getetag><d:getcontentlength>999999</d:getcontentlength></d:prop>
                      <d:status>HTTP/1.1 404 Not Found</d:status>
                    </d:propstat>
                  </d:response>
                </d:multistatus>
                """.trimIndent()
            server.enqueue(MockResponse().setResponseCode(207).setBody(body))

            val result = TestSupport.newTransport(server).list(inboxPath) as WebDavResult.Success
            val entry = result.value.first { it.name == "msg-a" }
            // The 200 block's etag/length win, not the 404 block's empty etag / 999999 length.
            assertEquals("\"real-etag\"", entry.etag)
            assertEquals(42L, entry.contentLength)
        }

    // review finding 9: a multistatus labeled non-UTF-8 (here ISO-8859-1) with a non-ASCII href is
    // parsed from the raw bytes per the XML declaration, not double-decoded — the name decodes right.
    @Test
    fun propfind_parses_non_utf8_charset_from_raw_bytes() =
        runTest {
            // 'é' is 0xE9 in ISO-8859-1. The href segment name is "caf<0xE9>".
            val xml =
                "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>" +
                    "<d:multistatus xmlns:d=\"DAV:\">" +
                    "<d:response><d:href>/${TestSupport.CHAT_ROOT}/$inboxPath/</d:href>" +
                    "<d:propstat><d:status>HTTP/1.1 200 OK</d:status>" +
                    "<d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>" +
                    "<d:response><d:href>/${TestSupport.CHAT_ROOT}/$inboxPath/café</d:href>" +
                    "<d:propstat><d:status>HTTP/1.1 200 OK</d:status>" +
                    "<d:prop><d:getetag>\"e\"</d:getetag></d:prop></d:propstat></d:response>" +
                    "</d:multistatus>"
            val latin1Bytes = xml.toByteArray(Charsets.ISO_8859_1)
            server.enqueue(MockResponse().setResponseCode(207).setBody(okio.Buffer().write(latin1Bytes)))

            val result = TestSupport.newTransport(server).list(inboxPath) as WebDavResult.Success
            // The non-ASCII name decodes to "café" — not mojibake from a double-decode.
            assertTrue(result.value.any { it.name == "café" })
        }

    // review finding 1: a GET whose body exceeds the message-file size cap is rejected (not buffered
    // whole, not surfaced as a valid message). Oversize → NotReady (skip), never an OOM.
    @Test
    fun oversize_get_body_is_rejected_not_buffered() =
        runTest {
            val token = OrderToken.build(1_000L, "alice", 1)
            // Name commits to the hash of a small payload; the server then returns a body far over
            // the 1 MiB cap. Even if its hash matched, the cap must trip first.
            val small = Envelope.write("small".toByteArray())
            val name = MessageId.messageId(token, small)
            val path = "$inboxPath/$name"

            val oversize = ByteArray(2 * 1024 * 1024) { 0x41 } // 2 MiB > 1 MiB cap
            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(oversize)))

            val result = TestSupport.newTransport(server).read(path, name)
            assertTrue(result is WebDavResult.Success)
            assertEquals(ReadResult.NotReady, (result as WebDavResult.Success).value)
        }

    // review finding 6: a GET returning 404 is a benign race (file deleted concurrently / lag) and
    // maps to NotReady (skip), the same family as delete()'s 404→success — NOT a TransportError.
    @Test
    fun get_404_is_not_ready_not_transport_error() =
        runTest {
            val token = OrderToken.build(1_000L, "alice", 1)
            val framed = Envelope.write("gone".toByteArray())
            val name = MessageId.messageId(token, framed)

            server.enqueue(MockResponse().setResponseCode(404))
            val result = TestSupport.newTransport(server).read("$inboxPath/$name", name)
            assertTrue(result is WebDavResult.Success)
            assertEquals(ReadResult.NotReady, (result as WebDavResult.Success).value)
        }

    // §3: a complete file whose recomputed hash matches the name reads back the opaque blob.
    @Test
    fun reader_accepts_complete_matching_file() =
        runTest {
            val token = OrderToken.build(1_000L, "alice", 1)
            val blob = "complete-payload".toByteArray()
            val framed = Envelope.write(blob)
            val name = MessageId.messageId(token, framed)
            val path = "$inboxPath/$name"

            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(framed)))
            val result = TestSupport.newTransport(server).read(path, name)
            val value = (result as WebDavResult.Success).value
            assertTrue(value is ReadResult.Ready && value.blob.contentEquals(blob))
        }
}
