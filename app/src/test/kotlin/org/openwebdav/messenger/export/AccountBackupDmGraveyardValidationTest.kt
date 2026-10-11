package org.openwebdav.messenger.export

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class AccountBackupDmGraveyardValidationTest {
    @Test
    fun initiatorEvidenceValidatesWithoutDmKeyAndRejectsTamperingAndContextMismatches() {
        val fixture = AccountBackupDmGraveyardFixture()
        val initiator = ExportTestSupport.freshIdentity()
        val signer = initiator.copySignPublic()
        val deletion = fixture.deletion(initiator)
        assertTrue(fixture.isAuthenticated(deletion, signer))
        assertFalse(fixture.isAuthenticated(deletion.copy(tombstonePlaintextBytesBase64 = null), signer))
        assertFalse(fixture.isAuthenticated(deletion.copy(chatId = "wrong-chat"), signer))
        assertFalse(fixture.isAuthenticated(deletion, signer, "wrong-community"))
        assertFalse(fixture.isAuthenticated(deletion.copy(originHash = "b".repeat(32)), signer))
        assertFalse(fixture.isAuthenticated(deletion.copy(tombstoneHash = "b".repeat(32)), signer))
        assertFalse(fixture.isAuthenticated(fixture.deletion(initiator, wrongPeer = true), signer))

        val signed = Base64.getDecoder().decode(deletion.tombstonePlaintextBytesBase64)
        signed[signed.lastIndex] = (signed.last().toInt() xor 1).toByte()
        assertFalse(
            fixture.isAuthenticated(
                deletion.copy(tombstonePlaintextBytesBase64 = Base64.getEncoder().encodeToString(signed)),
                signer,
            ),
        )
    }
}
