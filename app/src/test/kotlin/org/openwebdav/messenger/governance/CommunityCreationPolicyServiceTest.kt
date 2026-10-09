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
import org.openwebdav.messenger.transport.WebDavResult
import java.util.concurrent.atomic.AtomicReference

class CommunityCreationPolicyServiceTest {
    @Test
    fun policy_read_advances_high_water_and_rollback_fails_closed() =
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
                                    .setBody(TestSupport.multistatus("governance/policies", contentHash.get() to "etag"))
                            "GET" -> MockResponse().setResponseCode(200).setBody(okio.Buffer().write(storedFile.get()))
                            else -> MockResponse().setResponseCode(404)
                        }
                }
            server.start()
            try {
                val transport = TestSupport.newTransport(server)
                val identityCrypto = IdentityTestSupport.identityCrypto()
                val host = identityCrypto.generateIdentity()
                val key = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 5 })
                val envelopes = CommunityGovernanceEnvelopeCodec(Aead(CryptoTestSupport.native()))
                val policyBytes =
                    CommunityCreationPolicyCodec.sign(
                        "community-1",
                        1,
                        allowPublic = false,
                        allowPrivate = true,
                        grandfatheredOriginHashes = emptyList(),
                        signingSecret = host.copySignSecret(),
                        crypto = identityCrypto,
                    )
                val reference =
                    CommunityGovernanceWriter(transport, envelopes)
                        .seal(GovernanceObjectKind.POLICY, "community-1", key, policyBytes)
                contentHash.set(reference.contentHash)
                assertTrue(CommunityGovernanceWriter(transport, envelopes).publish(reference) is WebDavResult.Success)
                val reader = CommunityGovernanceReader(transport, envelopes)
                val service =
                    CommunityCreationPolicyService(
                        reader,
                        "community-1",
                        key,
                        host.copySignPublic(),
                        false,
                        identityCrypto,
                    ) { revision, hash ->
                        assertEquals(1L, revision)
                        assertEquals(reference.contentHash, hash)
                        PolicyRevisionDecision.Accepted
                    }

                val result = service.read()
                assertTrue(result is CommunityCreationPolicyService.Result.Policy)
                assertEquals(false, (result as CommunityCreationPolicyService.Result.Policy).value.allowPublic)
                val rollback =
                    CommunityCreationPolicyService(
                        reader,
                        "community-1",
                        key,
                        host.copySignPublic(),
                        true,
                        identityCrypto,
                    ) { _, _ -> PolicyRevisionDecision.Rollback }
                assertEquals(CommunityCreationPolicyService.Result.Blocked, rollback.read())
            } finally {
                server.shutdown()
            }
        }
}
