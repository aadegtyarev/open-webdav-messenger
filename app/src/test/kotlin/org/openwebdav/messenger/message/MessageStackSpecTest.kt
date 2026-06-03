package org.openwebdav.messenger.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stack-spec tests for the §8 message model: each asserts a cited rule from `docs/stack-notes.md` (the
 * Crypto AEAD + Ed25519 sub-sections), referencing the source URL — verifying the rule, not the coder's
 * own mapping.
 */
class MessageStackSpecTest {
    private val serializer = MessageTestSupport.serializer()
    private val parser = MessageTestSupport.parser()
    private val identity = MessageTestSupport.identityCrypto()
    private val messageCrypto = MessageTestSupport.messageCrypto()

    // ed25519_message_verify_rejects_on_failure — a bad message signature must hard-reject (libsodium
    // crypto_sign_verify_detached returns -1 → treat as a hard reject, never best-effort). The §8.3
    // per-message signature is exactly this primitive over the signed-payload range.
    // Source: https://doc.libsodium.org/public-key_cryptography/public-key_signatures
    @Test
    fun ed25519_message_verify_rejects_on_failure() {
        val id = MessageTestSupport.newIdentity()
        val bytes = serializer.signAndSerialize(MessageTestSupport.textMessage(id), id.copySignSecret())
        val signatureStart = bytes.size - MessageFormat.SIGNATURE_BYTES

        // Sanity: the unmodified signature verifies (the 0-success path).
        val signedPayload = bytes.copyOfRange(0, signatureStart)
        val signature = bytes.copyOfRange(signatureStart, bytes.size)
        assertTrue(identity.verify(signature, signedPayload, id.copySignPublic()))

        // Corrupt the trailing signature → verify must return false (the -1 hard-reject), and the parser
        // must surface a typed rejection, never accept the message.
        val bad = bytes.copyOf()
        bad[bad.size - 1] = (bad[bad.size - 1].toInt() xor 0xFF).toByte()
        val badSig = bad.copyOfRange(signatureStart, bad.size)
        assertFalse(identity.verify(badSig, signedPayload, id.copySignPublic()))
        assertEquals(RejectReason.BAD_SIGNATURE, (parser.parse(bad) as ParseResult.Rejected).reason)
    }

    // message_is_aead_plaintext — the signed+serialized §8 bytes are EXACTLY what Aead.seal encrypts and
    // Aead.open returns; the model rides inside the existing envelope, no separate channel (§8 relationship
    // to the outer frame; the AEAD plaintext is opaque to libsodium). codec-id stays 0x00 (none).
    // Source: https://doc.libsodium.org/secret-key_cryptography/aead
    @Test
    fun message_is_aead_plaintext() {
        val id = MessageTestSupport.newIdentity()
        val key = MessageTestSupport.fixedChatKey()
        val plaintext = serializer.signAndSerialize(MessageTestSupport.textMessage(id), id.copySignSecret())

        // Seal the exact §8 bytes, then open — the recovered plaintext is byte-identical to what we sealed.
        val envelopeBytes = messageCrypto.sealEnvelope(key, plaintext)
        val opened = messageCrypto.openEnvelope(key, envelopeBytes)
        opened as org.openwebdav.messenger.crypto.OpenResult.Opened
        assertTrue("AEAD-opened bytes must equal the signed+serialized §8 plaintext", plaintext.contentEquals(opened.bytes))

        // And those exact opened bytes parse+verify to the message — no side channel.
        assertTrue(parser.parse(opened.bytes) is ParseResult.Parsed)
    }
}
