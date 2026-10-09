package org.openwebdav.messenger.governance

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
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
