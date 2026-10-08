package org.openwebdav.messenger.ui.participants

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.app.VerifiedParticipant

class ParticipantOrderingTest {
    @Test
    fun self_precedes_name_sorted_peers_and_digest_breaks_ties() {
        val firstAlex = "a".repeat(10) + "1" + "0".repeat(53)
        val secondAlex = "a".repeat(10) + "2" + "0".repeat(53)
        val rows =
            listOf(
                VerifiedParticipant("alex", secondAlex, false),
                VerifiedParticipant("Alex", firstAlex, false),
                VerifiedParticipant("Zoe", "c".repeat(64), false),
                VerifiedParticipant("Self", "f".repeat(64), true),
            )
        val ordered = orderedParticipants(rows)
        assertEquals(listOf("f".repeat(64), firstAlex, secondAlex, "c".repeat(64)), ordered.map { it.stableKey })
        assertEquals(11, ordered[1].fingerprint.length)
        assertEquals(11, ordered[2].fingerprint.length)
    }

    @Test
    fun colliding_maximum_prefixes_use_unique_bounded_labels_and_full_keys() {
        val first = "a".repeat(16) + "1" + "0".repeat(47)
        val second = "a".repeat(16) + "2" + "0".repeat(47)
        val rows =
            orderedParticipants(
                listOf(
                    VerifiedParticipant("Alex", first, false),
                    VerifiedParticipant("Alex", second, false),
                ),
            )
        assertEquals(2, rows.map { it.stableKey }.distinct().size)
        assertEquals(2, rows.map { it.fingerprint }.distinct().size)
        assertEquals(listOf(16, 18), rows.map { it.fingerprint.length })
        assertTrue(rows.all { it.fingerprint.length <= 18 })
        assertTrue(rows.none { it.fingerprint.contains(first) || it.fingerprint.contains(second) })
    }
}
