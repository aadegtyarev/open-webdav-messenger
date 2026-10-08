package org.openwebdav.messenger.app

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

internal object RosterCacheText {
    fun encode(
        value: String,
        maxBytes: Int,
    ): ByteArray {
        val buffer =
            Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
        require(buffer.remaining() <= maxBytes)
        return ByteArray(buffer.remaining()).also(buffer::get)
    }

    fun decode(bytes: ByteArray): String {
        val decoder =
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }
}
