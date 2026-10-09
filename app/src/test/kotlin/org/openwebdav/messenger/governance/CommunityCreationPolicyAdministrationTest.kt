package org.openwebdav.messenger.governance

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.CryptoTestSupport
import org.openwebdav.messenger.identity.IdentityTestSupport
import org.openwebdav.messenger.transport.TestSupport

class CommunityCreationPolicyAdministrationTest {
    @Test
    fun disablingPublicCreationRequiresScanAndGrandfathersVerifiedOrigin() =
        runTest {
            val server = MockWebServer()
            val crypto = IdentityTestSupport.identityCrypto()
            val host = crypto.generateIdentity()
            val creator = crypto.generateIdentity()
            val key = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 7 })
            val envelope = CommunityGovernanceEnvelopeCodec(Aead(CryptoTestSupport.native()))
            val origin =
                CommunityGroupOrigin(
                    "community-1",
                    CommunityCreationPolicyTestSupport.CHAT_ID,
                    org.openwebdav.messenger.chatdirectory.ChatAccess.PUBLIC,
                    creator.copySignPublic(),
                    creator.copyBoxPublic(),
                    ByteArray(32) { 3 },
                )
            val signedOrigin = CommunityGroupOriginCodec.sign(origin, creator.copySignSecret(), crypto)
            val storedOrigin = envelope.seal(GovernanceObjectKind.GROUP_ORIGIN, "community-1", key, signedOrigin)
            val originHash = envelope.contentHash(storedOrigin)
            var uploadedPolicy: ByteArray? = null
            var accepted = 0
            server.dispatcher =
                CommunityCreationPolicyTestSupport.dispatcher(
                    originHash,
                    storedOrigin,
                    policyUpload = { uploadedPolicy = it },
                )
            server.start()
            try {
                val transport = TestSupport.newTransport(server)
                val envelopes = CommunityGovernanceEnvelopeCodec(Aead(CryptoTestSupport.native()))
                val reader = CommunityGovernanceReader(transport, envelopes)
                val writer = CommunityGovernanceWriter(transport, envelopes)
                val result =
                    CommunityCreationPolicyTestSupport.administration(reader, writer, key, host, crypto) { revision, _ ->
                        accepted = revision.toInt()
                        PolicyRevisionDecision.Accepted
                    }.update(allowPublic = false, allowPrivate = true)
                val policy = (result as CommunityCreationPolicyAdministration.Result.Published).policy
                assertEquals(
                    listOf(CommunityGroupOriginCodec.contentAddress(signedOrigin)),
                    policy.grandfatheredOriginHashes,
                )
                assertEquals(1, accepted)
                assertTrue(uploadedPolicy != null)
            } finally {
                server.shutdown()
            }
        }
}
