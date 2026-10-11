package org.openwebdav.messenger.governance

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.CryptoTestSupport
import org.openwebdav.messenger.identity.IdentityTestSupport
import org.openwebdav.messenger.transport.TestSupport

class CommunityCreationPolicyEquivocationTest {
    @Test
    fun equivocal_policy_revision_blocks_without_advancing_high_water() =
        runTest {
            val server = MockWebServer()
            val crypto = IdentityTestSupport.identityCrypto()
            val host = crypto.generateIdentity()
            val key = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 5 })
            val envelopes = CommunityGovernanceEnvelopeCodec(Aead(CryptoTestSupport.native()))
            val files =
                listOf(true, false).associate { allowPublic ->
                    val signed =
                        CommunityCreationPolicyCodec.sign(
                            "community-1",
                            3,
                            allowPublic,
                            !allowPublic,
                            emptyList(),
                            host.copySignSecret(),
                            crypto,
                        )
                    val stored = envelopes.seal(GovernanceObjectKind.POLICY, "community-1", key, signed)
                    envelopes.contentHash(stored) to stored
                }
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when (request.method) {
                            "PROPFIND" ->
                                MockResponse().setResponseCode(207)
                                    .setHeader("Content-Type", "application/xml; charset=utf-8")
                                    .setBody(
                                        TestSupport.multistatus(
                                            "governance/policies",
                                            *files.keys.map { it to "etag" }.toTypedArray(),
                                        ),
                                    )
                            "GET" ->
                                MockResponse().setResponseCode(200).setBody(
                                    okio.Buffer().write(files.getValue(request.requestUrl!!.encodedPathSegments.last())),
                                )
                            else -> MockResponse().setResponseCode(404)
                        }
                }
            server.start()
            try {
                var accepted = false
                val result =
                    CommunityCreationPolicyService(
                        CommunityGovernanceReader(TestSupport.newTransport(server), envelopes),
                        "community-1",
                        key,
                        host.copySignPublic(),
                        true,
                        crypto,
                    ) { _, _ ->
                        accepted = true
                        PolicyRevisionDecision.Accepted
                    }.read()

                assertEquals(CommunityCreationPolicyService.Result.Blocked, result)
                assertFalse(accepted)
            } finally {
                server.shutdown()
            }
        }
}
