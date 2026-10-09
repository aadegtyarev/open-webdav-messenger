package org.openwebdav.messenger.governance

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.CryptoTestSupport
import org.openwebdav.messenger.identity.IdentityTestSupport
import org.openwebdav.messenger.transport.TestSupport
import java.util.concurrent.atomic.AtomicReference

class CommunityTombstoneServiceTest {
    @Test
    fun publish_verifies_append_and_retry_is_idempotent() =
        runTest {
            val server = MockWebServer()
            val bytes = AtomicReference<ByteArray>()
            val hash = AtomicReference<String?>()
            server.dispatcher = dispatcher(bytes, hash)
            server.start()
            try {
                val transport = TestSupport.newTransport(server)
                val envelope = CommunityGovernanceEnvelopeCodec(Aead(CryptoTestSupport.native()))
                val reader = CommunityGovernanceReader(transport, envelope)
                val writer = CommunityGovernanceWriter(transport, envelope)
                val crypto = IdentityTestSupport.identityCrypto()
                val signer = crypto.generateIdentity()
                val record =
                    CommunityTombstone(
                        "community-1",
                        CHAT_ID,
                        "a".repeat(32),
                        TombstoneScope.GROUP,
                        1,
                        TombstoneSignerRole.GROUP_CREATOR,
                        signer.copySignPublic(),
                        emptyList(),
                    )
                val key = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 6 })
                val service = CommunityTombstoneService(reader, writer, envelope, crypto)

                val published = service.publish(record, signer.copySignSecret(), key)
                assertTrue(published is CommunityTombstoneService.Result.Published)
                assertEquals(CommunityTombstoneService.Result.AlreadyPresent, service.publish(record, signer.copySignSecret(), key))
            } finally {
                server.shutdown()
            }
        }

    private fun dispatcher(
        bytes: AtomicReference<ByteArray>,
        hash: AtomicReference<String?>,
    ) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
            when (request.method) {
                "MKCOL" -> MockResponse().setResponseCode(201)
                "PUT" -> {
                    bytes.set(request.body.readByteArray())
                    hash.set(request.requestUrl!!.encodedPathSegments.last())
                    MockResponse().setResponseCode(201)
                }
                "PROPFIND" ->
                    MockResponse().setResponseCode(207).setHeader("Content-Type", "application/xml; charset=utf-8")
                        .setBody(
                            TestSupport.multistatus(
                                "governance/tombstones/group",
                                * (hash.get()?.let { arrayOf(it to "e") } ?: emptyArray()),
                            ),
                        )
                "GET" -> MockResponse().setResponseCode(200).setBody(okio.Buffer().write(bytes.get()))
                else -> MockResponse().setResponseCode(404)
            }
    }

    private companion object {
        const val CHAT_ID = "abcdefghijklmnopqrstuvwxyz234567"
    }
}
