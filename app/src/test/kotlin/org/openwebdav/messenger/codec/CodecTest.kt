package org.openwebdav.messenger.codec

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openwebdav.messenger.protocol.Envelope

/**
 * Unit tests for [Codec] enum: `fromId` dispatch, unknown codec-id rejection (reject-don't-guess, §7).
 */
class CodecTest {
    @Test
    fun `fromId returns NONE for 0x00`() {
        assertEquals(Codec.NONE, Codec.fromId(Envelope.CODEC_NONE))
    }

    @Test
    fun `fromId returns DEFLATE for 0x01`() {
        assertEquals(Codec.DEFLATE, Codec.fromId(Envelope.CODEC_DEFLATE))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown codec-id 0x02 throws`() {
        Codec.fromId(0x02)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown codec-id 0xFF throws`() {
        Codec.fromId(0xFF.toByte())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown codec-id negative throws`() {
        Codec.fromId((-1).toByte())
    }

    @Test
    fun `enum ids match envelope constants`() {
        assertEquals(Envelope.CODEC_NONE, Codec.NONE.id)
        assertEquals(Envelope.CODEC_DEFLATE, Codec.DEFLATE.id)
    }
}
