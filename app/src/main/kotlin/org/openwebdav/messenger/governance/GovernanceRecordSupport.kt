package org.openwebdav.messenger.governance

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal object GovernanceRecordSupport {
    const val MAX_COMMUNITY_ID_BYTES = 96

    fun isValidCommunityId(value: String): Boolean = value.matches(ID_PATTERN)

    fun strictUtf8(bytes: ByteArray): String =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,96}")
}
