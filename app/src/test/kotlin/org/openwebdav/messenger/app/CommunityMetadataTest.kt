package org.openwebdav.messenger.app

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.crypto.LazySodiumCrypto
import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.sync.SyncTestSupport
import org.openwebdav.messenger.transport.WebDavResult
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CommunityMetadataTest {
    private val sodium = SodiumJava()
    private val crypto = IdentityCrypto(LazySodiumCrypto(LazySodiumJava(sodium)))

    @Test
    fun sign_and_verify_round_trip() {
        val hostIdentity = crypto.generateIdentity()
        val metadata = CommunityMetadata(minPollIntervalSeconds = 60)

        val payloadBytes = """{"minPollIntervalSeconds":60}""".toByteArray(Charsets.UTF_8)
        val signSecret = hostIdentity.copySignSecret()
        val signature = crypto.sign(payloadBytes, signSecret)
        val fileBytes = signature + payloadBytes
        signSecret.fill(0)

        assertEquals(64, signature.size)
        assertEquals(64 + payloadBytes.size, fileBytes.size)
        assertTrue(crypto.verify(signature, payloadBytes, hostIdentity.copySignPublic()))
    }

    @Test
    fun tampered_payload_fails_verification() {
        val hostIdentity = crypto.generateIdentity()
        val payloadBytes = """{"minPollIntervalSeconds":60}""".toByteArray(Charsets.UTF_8)
        val signSecret = hostIdentity.copySignSecret()
        val signature = crypto.sign(payloadBytes, signSecret)
        signSecret.fill(0)

        val tamperedPayload = payloadBytes.copyOf()
        tamperedPayload[tamperedPayload.size - 1] = (tamperedPayload.last() + 1).toByte()

        assertTrue(!crypto.verify(signature, tamperedPayload, hostIdentity.copySignPublic()))
    }

    @Test
    fun wrong_signer_fails_verification() {
        val hostIdentity = crypto.generateIdentity()
        val attackerIdentity = crypto.generateIdentity()
        val payloadBytes = """{"minPollIntervalSeconds":60}""".toByteArray(Charsets.UTF_8)

        val attackerSecret = attackerIdentity.copySignSecret()
        val attackerSig = crypto.sign(payloadBytes, attackerSecret)
        attackerSecret.fill(0)

        assertTrue(!crypto.verify(attackerSig, payloadBytes, hostIdentity.copySignPublic()))
    }

    @Test
    fun write_propagates_http_rejection_as_typed_result() =
        runBlocking {
            val server = MockWebServer().apply { start() }
            try {
                server.enqueue(MockResponse().setResponseCode(201))
                server.enqueue(MockResponse().setResponseCode(412))
                val result =
                    CommunityMetadata.write(
                        SyncTestSupport.transport(server),
                        CommunityMetadata(60, 30),
                        crypto.generateIdentity(),
                        crypto,
                    )
                assertEquals(WebDavResult.Conflict, result)
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun write_stops_after_collection_creation_failure() =
        runBlocking {
            val server = MockWebServer().apply { start() }
            try {
                server.enqueue(MockResponse().setResponseCode(401))
                val result =
                    CommunityMetadata.write(
                        SyncTestSupport.transport(server),
                        CommunityMetadata(60, 30),
                        crypto.generateIdentity(),
                        crypto,
                    )
                assertTrue(result is WebDavResult.TransportError)
                assertEquals(1, server.requestCount)
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun floorSeconds_clamps_to_default_when_null() {
        assertEquals(
            CommunityMetadata.DEFAULT_FLOOR_SECONDS,
            CommunityMetadata.floorSeconds(null),
        )
    }

    @Test
    fun floorSeconds_uses_max_of_remote_and_default() {
        assertEquals(120, CommunityMetadata.floorSeconds(120))
        assertEquals(300, CommunityMetadata.floorSeconds(300))
        assertEquals(CommunityMetadata.DEFAULT_FLOOR_SECONDS, CommunityMetadata.floorSeconds(30))
    }
}
