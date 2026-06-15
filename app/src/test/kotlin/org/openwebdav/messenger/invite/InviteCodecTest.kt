package org.openwebdav.messenger.invite

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the `owdm1:` invite codec (`ui-chat-surface` plan Test plan): round-trip every field
 * byte-identically, and reject-don't-guess for a foreign / malformed token. Pure JVM — no native crypto, no
 * Android — the codec's framing (json/gzip/base64url) is self-contained. All NEW tests.
 */
class InviteCodecTest {
    private val codec = InviteCodec()

    private fun sampleToken(): InviteToken =
        InviteToken(
            // Obvious fake values (SC21 — no secret material in source/tests).
            baseUrl = "https://disk.example.test",
            username = "owner-login",
            appPassword = "fake-app-password-not-real",
            chatRoot = "owdm/community-root",
            chatId = "chatidchatidchatidchatid01",
            chatKey = ByteArray(InviteToken.CHAT_KEY_BYTES) { (it * 7 + 1).toByte() },
            communityName = "Тестовое сообщество 🚀 with unicode + \"quotes\"",
        )

    /** invite_token_round_trips_owner_to_member — every field recovered byte-identically. */
    @Test
    fun invite_token_round_trips_owner_to_member() =
        runTest {
            val original = sampleToken()

            val encoded = codec.encode(original)
            assertTrue("token must carry the owdm1: scheme prefix", encoded.startsWith(InviteCodec.PREFIX))
            val result = codec.decode(encoded)

            assertTrue(result is InviteCodec.Result.Decoded)
            val recovered = (result as InviteCodec.Result.Decoded).token
            assertEquals(original.baseUrl, recovered.baseUrl)
            assertEquals(original.username, recovered.username)
            assertEquals(original.appPassword, recovered.appPassword)
            assertEquals(original.chatRoot, recovered.chatRoot)
            assertEquals(original.chatId, recovered.chatId)
            assertArrayEquals(original.chatKey, recovered.chatKey)
            assertEquals(original.communityName, recovered.communityName)
            assertEquals(original, recovered)
        }

    /**
     * invite_decode_rejects_non_owdm_or_malformed_token — a non-owdm string (random QR / noise), a bad
     * base64url / bad gzip, or a missing field is a typed Rejected (no partial config, no crash). Scenario 4.
     */
    @Test
    fun invite_decode_rejects_non_owdm_or_malformed_token() {
        // A random QR / poster string — wrong prefix.
        assertReject(codec.decodeBlocking("https://some-product.example/promo"))
        assertReject(codec.decodeBlocking("just some noise"))
        assertReject(codec.decodeBlocking(""))
        // Right prefix, garbage base64url.
        assertReject(codec.decodeBlocking("owdm1:!!!not-base64!!!"))
        // Right prefix, valid base64url but not a gzip stream.
        assertReject(codec.decodeBlocking("owdm1:YWJjZGVmZ2g"))
        // Right prefix + valid gzip of NON-owdm JSON (a flat object missing the version + fields).
        val foreignGzip = InviteCodec.PREFIX + gzipBase64("{\"hello\":\"world\"}")
        assertReject(codec.decodeBlocking(foreignGzip))
        // Right prefix + valid gzip of a flat object that is JSON but not the invite shape (nested).
        val notFlat = InviteCodec.PREFIX + gzipBase64("{\"v\":{\"nested\":\"x\"}}")
        assertReject(codec.decodeBlocking(notFlat))
    }

    /** A well-formed token with a chat-key of the wrong length is rejected (not silently accepted). */
    @Test
    fun invite_decode_rejects_wrong_length_chat_key() {
        // Build a valid owdm1 JSON but with a 4-byte (not 32-byte) base64url key.
        val badKey = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(4))
        val json =
            "{\"v\":\"1\",\"u\":\"https://x.test\",\"n\":\"a\",\"p\":\"b\"," +
                "\"r\":\"c\",\"c\":\"d\",\"k\":\"$badKey\",\"m\":\"e\"}"
        assertReject(codec.decodeBlocking(InviteCodec.PREFIX + gzipBase64(json)))
    }

    /** The decoded token never prints the app-password or the chat key (bearer/secret material). */
    @Test
    fun invite_token_toString_is_redacted() {
        val s = sampleToken().toString()
        assertFalse("app-password must not appear in toString", s.contains("fake-app-password-not-real"))
        assertTrue(s.contains("appPassword=***"))
        assertTrue(s.contains("chatKey=***"))
    }

    private fun assertReject(result: InviteCodec.Result) {
        assertTrue("expected Rejected, got $result", result is InviteCodec.Result.Rejected)
        // Reject-don't-guess: there is no partial token to read.
        assertNull((result as? InviteCodec.Result.Decoded)?.token)
    }

    /** gzip [json] and url-base64-encode it the same way the codec does, for crafting reject fixtures. */
    private fun gzipBase64(json: String): String {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(out).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }
}
