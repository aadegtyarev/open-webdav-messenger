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

class CommunityCreationPolicyHighWaterTest {
    @Test
    fun policy_read_advances_high_water_and_rollback_fails_closed() =
        runTest {
            val crypto = IdentityTestSupport.identityCrypto()
            val host = crypto.generateIdentity()
            val key = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 5 })
            val envelopes = CommunityGovernanceEnvelopeCodec(Aead(CryptoTestSupport.native()))
            val signed =
                CommunityCreationPolicyCodec.sign(
                    "community-1",
                    1,
                    false,
                    true,
                    emptyList(),
                    host.copySignSecret(),
                    crypto,
                )
            val stored = envelopes.seal(GovernanceObjectKind.POLICY, "community-1", key, signed)
            val hash = envelopes.contentHash(stored)
            val server = MockWebServer()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when (request.method) {
                            "PROPFIND" ->
                                MockResponse().setResponseCode(207)
                                    .setHeader("Content-Type", "application/xml; charset=utf-8")
                                    .setBody(TestSupport.multistatus("governance/policies", hash to "etag"))
                            "GET" -> MockResponse().setResponseCode(200).setBody(okio.Buffer().write(stored))
                            else -> MockResponse().setResponseCode(404)
                        }
                }
            server.start()
            try {
                var highWater = 0L
                val service =
                    CommunityCreationPolicyService(
                        CommunityGovernanceReader(TestSupport.newTransport(server), envelopes),
                        "community-1",
                        key,
                        host.copySignPublic(),
                        false,
                        crypto,
                    ) { revision, acceptedHash ->
                        assertEquals(1L, revision)
                        assertEquals(hash, acceptedHash)
                        highWater = revision
                        PolicyRevisionDecision.Accepted
                    }
                val result = service.read()
                assertTrue(result is CommunityCreationPolicyService.Result.Policy)
                assertEquals(false, (result as CommunityCreationPolicyService.Result.Policy).value.allowPublic)
                assertEquals(1L, highWater)

                var storedRevision = 2L
                val rollback =
                    CommunityCreationPolicyService(
                        CommunityGovernanceReader(TestSupport.newTransport(server), envelopes),
                        "community-1",
                        key,
                        host.copySignPublic(),
                        true,
                        crypto,
                    ) { revision, _ ->
                        if (revision < storedRevision) PolicyRevisionDecision.Rollback else PolicyRevisionDecision.Accepted
                    }
                assertEquals(CommunityCreationPolicyService.Result.Blocked, rollback.read())
                assertEquals(2L, storedRevision)
            } finally {
                server.shutdown()
            }
        }
}
