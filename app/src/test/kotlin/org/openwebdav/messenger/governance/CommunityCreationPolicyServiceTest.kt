package org.openwebdav.messenger.governance

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.openwebdav.messenger.chatdirectory.ChatAccess
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.CryptoTestSupport
import org.openwebdav.messenger.identity.IdentityTestSupport
import org.openwebdav.messenger.transport.TestSupport

class CommunityCreationPolicyServiceTest {
    @Test
    fun trusted_unavailable_policy_blocks_public_and_private_creation() =
        runTest {
            val server = MockWebServer()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(503)
                }
            server.start()
            try {
                val crypto = IdentityTestSupport.identityCrypto()
                val host = crypto.generateIdentity()
                val key = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 5 })
                val reader =
                    CommunityGovernanceReader(
                        TestSupport.newTransport(server),
                        CommunityGovernanceEnvelopeCodec(Aead(CryptoTestSupport.native())),
                    )
                val unavailable =
                    CommunityCreationPolicyService(
                        reader,
                        "community-1",
                        key,
                        host.copySignPublic(),
                        true,
                        crypto,
                    ) { _, _ -> error("unavailable policy must not advance trust") }.read()

                assertEquals(CommunityCreationPolicyService.Result.Blocked, unavailable)
                assertFalse(CommunityCreationPolicyRules.allowsCreation(unavailable, ChatAccess.PUBLIC))
                assertFalse(CommunityCreationPolicyRules.allowsCreation(unavailable, ChatAccess.PRIVATE))
            } finally {
                server.shutdown()
            }
        }
}
