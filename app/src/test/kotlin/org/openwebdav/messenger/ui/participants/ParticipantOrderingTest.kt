package org.openwebdav.messenger.ui.participants

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openwebdav.messenger.app.VerifiedParticipant

class ParticipantOrderingTest {
    @Test
    fun self_precedes_name_sorted_peers_and_fingerprint_breaks_ties() {
        val rows =
            listOf(
                VerifiedParticipant("alex", "b2", false),
                VerifiedParticipant("Alex", "a1", false),
                VerifiedParticipant("Zoe", "c3", false),
                VerifiedParticipant("Self", "z9", true),
            )
        assertEquals(listOf("z9", "a1", "b2", "c3"), orderedParticipants(rows).map { it.fingerprint })
    }
}
