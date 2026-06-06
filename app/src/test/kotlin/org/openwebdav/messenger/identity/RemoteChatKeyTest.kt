package org.openwebdav.messenger.identity

import com.goterl.lazysodium.interfaces.Box
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.OpenResult

/**
 * JVM unit tests for [IdentityCrypto.deriveRemoteChatKey] — the chat-id-bound DH derivation that fixes
 * blocker D10 (`docs/features/x25519-identity_plan.md` Test plan). Run against real lazysodium-java
 * (system libsodium) in `./gradlew test`, the same code the app runs on lazysodium-android behind
 * `NativeCrypto`. These are all NEW tests; the existing `agreeChatKey` suite is untouched.
 */
class RemoteChatKeyTest {
    private val native = IdentityTestSupport.native()
    private val identity = IdentityTestSupport.identityCrypto()
    private val header = byteArrayOf(0x4F, 0x57, 0x44, 0x4D, 0x01, 0x00, 0x00, 0x00)

    // derive_remote_chatkey_distinct_per_chat_id (D10 regression) — the SAME identity pair, two
    // different chat-ids → two DIFFERENT ChatKeys. This is the core blocker-fix test: today's bare
    // agreeChatKey returns one key for all chats between a pair; deriveRemoteChatKey binds the chat-id.
    @Test
    fun derive_remote_chatkey_distinct_per_chat_id() {
        val a = identity.generateIdentity()
        val b = identity.generateIdentity()
        val keyX = identity.deriveRemoteChatKey(a.copyBoxSecret(), b.copyBoxPublic(), "chat-x")
        val keyY = identity.deriveRemoteChatKey(a.copyBoxSecret(), b.copyBoxPublic(), "chat-y")
        assertEquals(ChatKey.KEY_BYTES, keyX.export().size)
        assertFalse(
            "two chats between the same pair must derive distinct keys (D10)",
            keyX.export().contentEquals(keyY.export()),
        )
    }

    // derive_remote_chatkey_symmetric_for_fixed_chat_id — A(aSecret, bBoxPublic, chatId) ==
    // B(bSecret, aBoxPublic, chatId): both members derive identical key bytes for the same chat-id,
    // so they talk with no shared secret ever sent over a channel.
    @Test
    fun derive_remote_chatkey_symmetric_for_fixed_chat_id() {
        val a = identity.generateIdentity()
        val b = identity.generateIdentity()
        val chatId = "shared-remote-chat"
        val keyA = identity.deriveRemoteChatKey(a.copyBoxSecret(), b.copyBoxPublic(), chatId)
        val keyB = identity.deriveRemoteChatKey(b.copyBoxSecret(), a.copyBoxPublic(), chatId)
        assertArrayEquals(keyA.export(), keyB.export())
        // A third party cannot reach the same key from its own secret + A's public.
        val c = identity.generateIdentity()
        val keyC = identity.deriveRemoteChatKey(c.copyBoxSecret(), a.copyBoxPublic(), chatId)
        assertFalse(keyA.export().contentEquals(keyC.export()))
    }

    // derive_remote_chatkey_never_equals_bare_pairwise — the per-chat key differs from the bare
    // agreeChatKey pairwise key for the same pair, proving the chat-id binding actually changes the
    // output (a chat-id-bound v2 derivation, not the v1 pairwise primitive).
    @Test
    fun derive_remote_chatkey_never_equals_bare_pairwise() {
        val a = identity.generateIdentity()
        val b = identity.generateIdentity()
        val pairwise = identity.agreeChatKey(a.copyBoxSecret(), b.copyBoxPublic())
        val remote = identity.deriveRemoteChatKey(a.copyBoxSecret(), b.copyBoxPublic(), "any-chat")
        assertFalse(
            "deriveRemoteChatKey must not equal the bare pairwise agreeChatKey output",
            pairwise.export().contentEquals(remote.export()),
        )
    }

    // cross_chat_isolation_end_to_end — seal a message under chat A's derived key, attempt to open it
    // under chat B's derived key (same identity pair) → OpenResult.Rejected. The chat-id binding gives
    // real cryptographic isolation between two chats of the same pair (the D10 weakness this closes).
    @Test
    fun cross_chat_isolation_end_to_end() {
        val a = identity.generateIdentity()
        val b = identity.generateIdentity()
        val keyChatA = identity.deriveRemoteChatKey(a.copyBoxSecret(), b.copyBoxPublic(), "chat-A")
        val keyChatB = identity.deriveRemoteChatKey(a.copyBoxSecret(), b.copyBoxPublic(), "chat-B")

        val aead = Aead(native)
        val plaintext = "chat-A only".toByteArray()
        val blob = aead.seal(keyChatA, header, plaintext)

        // Opening chat-A's ciphertext under chat-B's key is a typed rejection (no cross-chat read).
        assertEquals(OpenResult.Rejected, aead.open(keyChatB, header, blob))
        // Sanity: it still opens under its own chat-A key.
        val opened = aead.open(keyChatA, header, blob)
        assertTrue(opened is OpenResult.Opened)
        assertArrayEquals(plaintext, (opened as OpenResult.Opened).bytes)
    }

    // --- Stack-spec tests (assert a cited rule, not the coder's own mapping) -----------------------

    // derivation_does_not_use_raw_dh_output_as_key — the derived key is NOT the raw crypto_box_beforenm
    // shared secret; a KDF was applied. "do not feed the raw shared secret to the AEAD; derive from it."
    // Source: https://doc.libsodium.org/public-key_cryptography/authenticated_encryption
    @Test
    fun derivation_does_not_use_raw_dh_output_as_key() {
        val a = identity.generateIdentity()
        val b = identity.generateIdentity()
        val rawShared = native.boxBeforeNm(b.copyBoxPublic(), a.copyBoxSecret())
        assertEquals(Box.BEFORENMBYTES, rawShared.size)
        val key = identity.deriveRemoteChatKey(a.copyBoxSecret(), b.copyBoxPublic(), "kdf-applied")
        assertEquals(ChatKey.KEY_BYTES, key.export().size)
        assertFalse(
            "derived ChatKey must be KDF'd, never the raw DH shared secret",
            key.export().contentEquals(rawShared),
        )
    }

    // per_chat_kdf_context_binds_chat_id — the v2 derivation's output changes with the chat-id (the
    // chat-id is part of the hashed input), distinguishing it from the v1 fixed-context behaviour: a
    // distinct, versioned context per derivation purpose, chat-id mixed into the keyed-hash message.
    // Source: https://doc.libsodium.org/hashing/generic_hashing
    @Test
    fun per_chat_kdf_context_binds_chat_id() {
        val a = identity.generateIdentity()
        val b = identity.generateIdentity()
        // Same pair (same DH shared secret), three distinct chat-ids → three distinct keys: the chat-id
        // is genuinely hashed into the v2 message, not ignored as the v1 fixed context would.
        val k1 = identity.deriveRemoteChatKey(a.copyBoxSecret(), b.copyBoxPublic(), "id-1")
        val k2 = identity.deriveRemoteChatKey(a.copyBoxSecret(), b.copyBoxPublic(), "id-2")
        val k3 = identity.deriveRemoteChatKey(a.copyBoxSecret(), b.copyBoxPublic(), "id-3")
        assertFalse(k1.export().contentEquals(k2.export()))
        assertFalse(k2.export().contentEquals(k3.export()))
        assertFalse(k1.export().contentEquals(k3.export()))
        // Deterministic from the chat-id: the same chat-id re-derives the identical key.
        val k1Again = identity.deriveRemoteChatKey(a.copyBoxSecret(), b.copyBoxPublic(), "id-1")
        assertArrayEquals(k1.export(), k1Again.export())
    }
}
