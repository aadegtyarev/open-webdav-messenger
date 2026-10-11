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

class CommunityCreationPolicyAdministrationFailureTest {
    @Test
    fun incomplete_origin_listing_prevents_disabling_public_creation() =
        runTest {
            val server = MockWebServer()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        if (request.method == "PROPFIND" && request.path!!.contains("policies")) {
                            MockResponse().setResponseCode(207).setHeader("Content-Type", "application/xml; charset=utf-8")
                                .setBody(TestSupport.multistatus("governance/policies"))
                        } else if (request.method == "PROPFIND") {
                            MockResponse().setResponseCode(503)
                        } else {
                            MockResponse().setResponseCode(404)
                        }
                }
            server.start()
            try {
                val crypto = IdentityTestSupport.identityCrypto()
                val host = crypto.generateIdentity()
                val key = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 5 })
                val transport = TestSupport.newTransport(server)
                var accepted = false
                val result =
                    CommunityCreationPolicyAdministration(
                        CommunityGovernanceReader(transport, CommunityGovernanceEnvelopeCodec(Aead(CryptoTestSupport.native()))),
                        CommunityGovernanceWriter(transport, CommunityGovernanceEnvelopeCodec(Aead(CryptoTestSupport.native()))),
                        "community-1", key, host.copySignPublic(), host.copySignSecret(), 0, null, crypto,
                    ) { _, _ ->
                        accepted = true
                        PolicyRevisionDecision.Accepted
                    }.update(false, true)

                assertEquals(CommunityCreationPolicyAdministration.Result.Unavailable, result)
                assertFalse(accepted)
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun failedPolicyPutDoesNotAdvanceHighWater() =
        runTest {
            val server = MockWebServer()
            server.dispatcher = CommunityCreationPolicyTestSupport.dispatcher(null, null, {}, failPut = true)
            server.start()
            try {
                val crypto = IdentityTestSupport.identityCrypto()
                val host = crypto.generateIdentity()
                val key = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 5 })
                val envelope = CommunityGovernanceEnvelopeCodec(Aead(CryptoTestSupport.native()))
                val transport = TestSupport.newTransport(server)
                var accepted = false
                val result =
                    CommunityCreationPolicyTestSupport.administration(
                        CommunityGovernanceReader(transport, envelope),
                        CommunityGovernanceWriter(transport, envelope),
                        key,
                        host,
                        crypto,
                    ) { _, _ ->
                        accepted = true
                        PolicyRevisionDecision.Accepted
                    }.update(true, true)
                assertEquals(CommunityCreationPolicyAdministration.Result.Unavailable, result)
                assertFalse(accepted)
            } finally {
                server.shutdown()
            }
        }
}
