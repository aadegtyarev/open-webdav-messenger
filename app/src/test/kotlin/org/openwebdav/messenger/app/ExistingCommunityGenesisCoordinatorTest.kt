package org.openwebdav.messenger.app

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.CryptoTestSupport
import org.openwebdav.messenger.governance.CommunityGenesisCodec
import org.openwebdav.messenger.governance.CommunityGovernanceEnvelopeCodec
import org.openwebdav.messenger.governance.CommunityGovernanceReader
import org.openwebdav.messenger.governance.CommunityGovernanceWriter
import org.openwebdav.messenger.identity.IdentityTestSupport
import org.openwebdav.messenger.keystore.TrustPinResult
import org.openwebdav.messenger.transport.TestSupport

class ExistingCommunityGenesisCoordinatorTest {
    @Test
    fun host_migration_publishes_signed_genesis_then_pins_it() =
        runTest {
            val host = IdentityTestSupport.identityCrypto().generateIdentity()
            val crypto = IdentityTestSupport.identityCrypto()
            val key = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 8 })
            var uploaded: ByteArray? = null
            val server =
                MockWebServer().apply {
                    dispatcher = ExistingCommunityGenesisTestSupport.dispatcher(null, null) { uploaded = it }
                    start()
                }
            try {
                val envelopes = CommunityGovernanceEnvelopeCodec(Aead(CryptoTestSupport.native()))
                val transport = TestSupport.newTransport(server)
                var pinned: ByteArray? = null
                val result =
                    ExistingCommunityGenesisCoordinator(
                        COMMUNITY,
                        key,
                        host,
                        CommunityGovernanceReader(transport, envelopes),
                        CommunityGovernanceWriter(transport, envelopes),
                        crypto,
                        { true },
                        { bytes ->
                            pinned = bytes.copyOf()
                            TrustPinResult.PINNED
                        },
                    ).migrate()
                assertEquals(GenesisMigrationResult.Published, result)
                val record = checkNotNull(pinned)
                val decoded = CommunityGenesisCodec.verify(record, COMMUNITY, crypto)
                assertNotNull(decoded)
                assertTrue(checkNotNull(decoded).hostSigningPublicKey.contentEquals(host.copySignPublic()))
                assertNotNull(uploaded)
                assertTrue(uploaded!!.isNotEmpty())
            } finally {
                server.shutdown()
            }
        }

    private companion object {
        const val COMMUNITY = "community-1"
    }
}
