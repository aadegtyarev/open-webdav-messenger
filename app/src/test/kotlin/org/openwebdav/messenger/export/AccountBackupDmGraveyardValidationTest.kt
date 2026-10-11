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
        val deletion = fixture.deletion(initiator)
        assertTrue(fixture.isAuthenticated(deletion, fixture.participant(initiator)))
        assertFalse(fixture.isAuthenticated(deletion.copy(tombstonePlaintextBytesBase64 = null), fixture.participant(initiator)))
        assertFalse(fixture.isAuthenticated(deletion.copy(chatId = "wrong-chat"), fixture.participant(initiator)))
        assertFalse(fixture.isAuthenticated(deletion, fixture.participant(initiator), "wrong-community"))
        assertFalse(fixture.isAuthenticated(deletion.copy(originHash = "b".repeat(32)), fixture.participant(initiator)))
        assertFalse(fixture.isAuthenticated(deletion.copy(tombstoneHash = "b".repeat(32)), fixture.participant(initiator)))
        assertFalse(fixture.isAuthenticated(fixture.deletion(initiator, wrongPeer = true), fixture.participant(initiator)))

        val signed = Base64.getDecoder().decode(deletion.tombstonePlaintextBytesBase64)
        signed[signed.lastIndex] = (signed.last().toInt() xor 1).toByte()
        assertFalse(
            fixture.isAuthenticated(
                deletion.copy(tombstonePlaintextBytesBase64 = Base64.getEncoder().encodeToString(signed)),
                fixture.participant(initiator),
            ),
        )
    }
}
