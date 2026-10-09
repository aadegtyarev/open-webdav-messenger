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
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.CryptoTestSupport
import org.openwebdav.messenger.transport.TestSupport
import org.openwebdav.messenger.transport.WebDavResult
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class CommunityGovernanceTransportTest {
    @Test
    fun missing_governance_leaf_is_empty_but_other_http_errors_remain_errors() =
        runTest {
            val server = MockWebServer()
            val requests = AtomicInteger()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        if (requests.incrementAndGet() == 1) {
                            MockResponse().setResponseCode(404)
                        } else {
                            MockResponse().setResponseCode(503)
                        }
                }
            server.start()
            try {
                val reader =
                    CommunityGovernanceReader(
                        TestSupport.newTransport(server),
                        CommunityGovernanceEnvelopeCodec(Aead(CryptoTestSupport.native())),
                    )
                assertEquals(WebDavResult.Success(emptyList<String>()), reader.listHashes(GovernanceObjectKind.GENESIS))
                val unavailable = reader.listHashes(GovernanceObjectKind.GENESIS)
                assertTrue(unavailable is WebDavResult.TransportError && unavailable.code == 503)
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun publish_and_exact_hash_read_round_trip_through_webdav() =
        runTest {
            val server = MockWebServer()
            val storedFile = AtomicReference<ByteArray>()
            val contentHash = AtomicReference<String>()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when (request.method) {
                            "MKCOL" -> MockResponse().setResponseCode(201)
                            "PUT" -> {
                                storedFile.set(request.body.readByteArray())
                                MockResponse().setResponseCode(201)
                            }
                            "PROPFIND" ->
                                MockResponse()
                                    .setResponseCode(207)
                                    .setHeader("Content-Type", "application/xml; charset=utf-8")
                                    .setBody(TestSupport.multistatus("governance/genesis", contentHash.get() to "etag"))
                            "GET" -> MockResponse().setResponseCode(200).setBody(okio.Buffer().write(storedFile.get()))
                            else -> MockResponse().setResponseCode(404)
                        }
                }
            server.start()
            try {
                val transport = TestSupport.newTransport(server)
                val envelope = CommunityGovernanceEnvelopeCodec(Aead(CryptoTestSupport.native()))
                val key = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 4 })
                val writer = CommunityGovernanceWriter(transport, envelope)
                val reference = writer.seal(GovernanceObjectKind.GENESIS, "community-1", key, "signed-genesis".toByteArray())
                contentHash.set(reference.contentHash)
                assertTrue(writer.publish(reference) is WebDavResult.Success)
                assertEquals(reference.storedBytes.toList(), storedFile.get().toList())

                val reader = CommunityGovernanceReader(transport, envelope)
                val listed = reader.listHashes(GovernanceObjectKind.GENESIS) as WebDavResult.Success
                assertEquals(listOf(reference.contentHash), listed.value)
                val fetched = reader.fetch(GovernanceObjectKind.GENESIS, "community-1", reference.contentHash, key)
                assertTrue(fetched is WebDavResult.Success)
                assertArrayEquals("signed-genesis".toByteArray(), (fetched as WebDavResult.Success).value)
            } finally {
                server.shutdown()
            }
        }
}
