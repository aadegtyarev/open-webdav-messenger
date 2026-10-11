package org.openwebdav.messenger.governance

internal object CommunityDmOriginKeyOrder {
    val lists = Comparator<List<Byte>> { left, right -> compare(left.toByteArray(), right.toByteArray()) }

    fun sort(participants: List<CommunityDmParticipant>): List<CommunityDmParticipant> =
        participants.sortedWith { left, right -> compare(left.signingPublicKey, right.signingPublicKey) }

    private fun compare(
        left: ByteArray,
        right: ByteArray,
    ): Int =
        left.indices.firstNotNullOfOrNull { index ->
            val value = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
            value.takeIf { it != 0 }
        } ?: 0
}
