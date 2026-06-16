package org.openwebdav.messenger.app

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.crypto.LazySodiumCrypto
import org.openwebdav.messenger.identity.IdentityCrypto

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
