package org.openwebdav.messenger.governance

internal object CommunityDmOriginParticipants {
    fun matches(
        actual: List<CommunityDmParticipant>,
        expected: List<CommunityDmParticipant>,
    ): Boolean {
        if (actual.size != PARTICIPANTS || expected.size != PARTICIPANTS) return false
        val sorted = CommunityDmOriginKeyOrder.sort(expected)
        return actual.indices.all { index ->
            actual[index].signingPublicKey.contentEquals(sorted[index].signingPublicKey) &&
                actual[index].boxPublicKey.contentEquals(sorted[index].boxPublicKey)
        }
    }

    private const val PARTICIPANTS = 2
}
