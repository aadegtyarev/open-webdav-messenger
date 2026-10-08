package org.openwebdav.messenger.ui.participants

import org.openwebdav.messenger.app.VerifiedParticipant
import java.util.Locale

internal data class ParticipantRowModel(
    val participant: VerifiedParticipant,
    val fingerprint: String,
    val stableKey: String,
)

internal fun orderedParticipants(rows: List<VerifiedParticipant>): List<ParticipantRowModel> {
    val sorted =
        rows.sortedWith(
            compareByDescending<VerifiedParticipant> { it.isSelf }
                .thenBy { it.displayName.trim().lowercase(Locale.ROOT) }
                .thenBy { it.identityDigest },
        )
    val shownPrefixes = mutableMapOf<String, Int>()
    val duplicateDigests = sorted.groupingBy { it.identityDigest }.eachCount()
    val duplicateIndexes = mutableMapOf<String, Int>()
    return sorted.mapIndexed { index, participant ->
        val prefixLength = uniquePrefixLength(sorted, index)
        val prefix = participant.identityDigest.take(prefixLength)
        val collisionIndex = shownPrefixes.merge(prefix, 1, Int::plus) ?: 1
        val duplicateIndex = duplicateIndexes.merge(participant.identityDigest, 1, Int::plus) ?: 1
        val suffix = if (collisionIndex > 1) "-$collisionIndex" else ""
        ParticipantRowModel(
            participant = participant,
            fingerprint = prefix + suffix,
            stableKey = participant.identityDigest + if (duplicateDigests[participant.identityDigest]!! > 1) "-$duplicateIndex" else "",
        )
    }
}

private fun uniquePrefixLength(
    rows: List<VerifiedParticipant>,
    index: Int,
): Int {
    val digest = rows[index].identityDigest
    return (MIN_FINGERPRINT_LENGTH..MAX_FINGERPRINT_LENGTH).firstOrNull { length ->
        rows.indices.none { other ->
            other != index && rows[other].identityDigest != digest &&
                rows[other].identityDigest.startsWith(digest.take(length))
        }
    } ?: MAX_FINGERPRINT_LENGTH
}

private const val MIN_FINGERPRINT_LENGTH = 10
private const val MAX_FINGERPRINT_LENGTH = 16
