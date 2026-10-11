package org.openwebdav.messenger.app

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.CryptoTestSupport
import org.openwebdav.messenger.governance.CommunityGenesisCodec
import org.openwebdav.messenger.governance.CommunityGovernanceEnvelopeCodec
import org.openwebdav.messenger.governance.CommunityGovernanceReader
import org.openwebdav.messenger.governance.CommunityGovernanceWriter
import org.openwebdav.messenger.governance.GovernanceObjectKind
import org.openwebdav.messenger.identity.IdentityTestSupport
import org.openwebdav.messenger.keystore.TrustPinResult
import org.openwebdav.messenger.transport.TestSupport

class ExistingCommunityGenesisConflictTest {
    @Test
    fun fake_host_genesis_conflicts_and_is_never_pinned() =
        runTest {
            val crypto = IdentityTestSupport.identityCrypto()
            val expectedHost = crypto.generateIdentity()
            val attacker = crypto.generateIdentity()
            val key = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 3 })
            val envelopes = CommunityGovernanceEnvelopeCodec(Aead(CryptoTestSupport.native()))
            val genesis =
                CommunityGenesisCodec.sign(
                    "community-1",
                    attacker.copySignPublic(),
                    attacker.copyBoxPublic(),
                    ByteArray(32),
                    attacker.copySignSecret(),
                    crypto,
                )
            val stored = envelopes.seal(GovernanceObjectKind.GENESIS, "community-1", key, genesis)
            val server =
                MockWebServer().apply {
                    dispatcher = ExistingCommunityGenesisTestSupport.dispatcher(envelopes.contentHash(stored), stored) {}
                    start()
                }
            try {
                val transport = TestSupport.newTransport(server)
                var pinCalls = 0
                val result =
                    ExistingCommunityGenesisCoordinator(
                        "community-1",
                        key,
                        expectedHost,
                        CommunityGovernanceReader(transport, envelopes),
                        CommunityGovernanceWriter(transport, envelopes),
                        crypto,
                        { true },
                        {
                            pinCalls++
                            TrustPinResult.PINNED
                        },
                    ).migrate()
                assertEquals(GenesisMigrationResult.Conflict, result)
                assertEquals(0, pinCalls)
            } finally {
                server.shutdown()
            }
        }
}
