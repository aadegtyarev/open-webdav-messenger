package org.openwebdav.messenger.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.protocol.Envelope

/**
 * Envelope-integrated seal/open (`docs/protocol/webdav-layout.md` §5 + §5.1): the final file is
 * `header8 ‖ blob`, the header is bound as AAD, and codec-id stays 0x00 for the crypto feature.
 */
class MessageCryptoTest {
    private val crypto = MessageCrypto(CryptoTestSupport.aead())
    private val key = CryptoTestSupport.fixedKey()

    @Test
    fun seal_envelope_has_valid_header_then_open_roundtrips() {
        val plaintext = "envelope body".toByteArray()
        val file = crypto.sealEnvelope(key, plaintext)

        // First 8 bytes are the envelope header: magic OWDM, version 0x01, codec-id 0x00, flags 0, reserved 0.
        assertArrayEquals(byteArrayOf(0x4F, 0x57, 0x44, 0x4D), file.copyOfRange(0, 4))
        assertEquals(Envelope.ENVELOPE_VERSION, file[4])
        assertEquals(Envelope.CODEC_NONE, file[5]) // crypto feature never wires compression
        assertEquals(0x00.toByte(), file[6])
        assertEquals(0x00.toByte(), file[7])

        val opened = crypto.openEnvelope(key, file)
        assertTrue(opened is OpenResult.Opened)
        assertArrayEquals(plaintext, (opened as OpenResult.Opened).bytes)
    }

    @Test
    fun tampering_codec_id_byte_in_envelope_rejects() {
        val file = crypto.sealEnvelope(key, "x".toByteArray())
        // Flip codec-id (byte 5) to deflate (0x01): a defined codec, so Envelope.read accepts it,
        // but the AAD binding makes the AEAD open fail → Rejected (the header is authenticated).
        val tampered = file.copyOf()
        tampered[5] = Envelope.CODEC_DEFLATE
        assertEquals(OpenResult.Rejected, crypto.openEnvelope(key, tampered))
    }

    @Test
    fun non_envelope_bytes_reject() {
        // Bad magic → Envelope.read returns null → Rejected (reject, don't guess), no AEAD attempt.
        assertEquals(OpenResult.Rejected, crypto.openEnvelope(key, ByteArray(60)))
    }

    @Test
    fun wrong_key_on_envelope_rejects() {
        val file = crypto.sealEnvelope(key, "secret".toByteArray())
        assertEquals(OpenResult.Rejected, crypto.openEnvelope(CryptoTestSupport.fixedKey(seed = 3), file))
    }
}
