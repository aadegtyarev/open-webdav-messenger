package org.openwebdav.messenger.chatdirectory

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.LazySodiumCrypto
import org.openwebdav.messenger.crypto.MessageCrypto
import org.openwebdav.messenger.crypto.NativeCrypto
import org.openwebdav.messenger.identity.IdentityCrypto

/**
 * Instrumented chat-directory tests — run on the connected device via `./gradlew connectedAndroidTest`.
 * These exercise the REAL lazysodium-android native `.so` paths the §11 chat directory uses: the
 * XChaCha20-Poly1305 AEAD seal/open of a chat-descriptor entry under the community key, and the Ed25519
 * sign/verify of the entry's inner payload, on the device ABI with no `UnsatisfiedLinkError`
 * (`docs/stack-notes.md` Crypto: a missing ABI is only catchable on a device, decision-8 gate).
 *
 * The transport (PROPFIND/GET/PUT) is exercised off-device by the JVM MockWebServer suite; these device
 * tests gate the native crypto paths the chat directory adds on top of the existing substrates. Mirrors
 * `directory/DirectoryInstrumentedTest`.
 */
@RunWith(AndroidJUnit4::class)
class ChatDirectoryInstrumentedTest {
    // The lazysodium-ANDROID backend (the real bundled native `.so` per ABI) — the same backend
    // ChatDirectoryFactory / IdentityFactory wire on-device. One instance owns the native binding.
    private val native: NativeCrypto = LazySodiumCrypto(LazySodiumAndroid(SodiumAndroid()))

    private fun identityCrypto(): IdentityCrypto = IdentityCrypto(native)

    private fun chatDirectoryCrypto(): ChatDirectoryCrypto = ChatDirectoryCrypto.create(MessageCrypto(Aead(native)), identityCrypto())

    private fun communityKey(seed: Byte = 7): ChatKey = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { seed })

    // chat_directory_native_seal_sign_roundtrip — real lazysodium-android AEAD-seal + Ed25519-sign +
    // verify of a chat-descriptor entry on the device ABI with no UnsatisfiedLinkError.
    // Source: https://github.com/terl/lazysodium-android
    @Test
    fun chat_directory_native_seal_sign_roundtrip() {
        val identity = identityCrypto().generateIdentity()
        val key = communityKey()
        val crypto = chatDirectoryCrypto()
        val chatId = "on-device-chat".toByteArray()

        val sealed =
            crypto.sealDescriptor(
                chatId = chatId,
                kind = ChatKind.GROUP,
                access = ChatAccess.PRIVATE,
                title = "On-Device Room",
                signingPublic = identity.copySignPublic(),
                versionCounter = 1,
                signingSecret = identity.copySignSecret(),
                communityKey = key,
            )
        val parsed = crypto.openDescriptor(sealed, key)
        assertTrue(parsed is ChatParseResult.Parsed)
        val d = (parsed as ChatParseResult.Parsed).descriptor
        assertArrayEquals(chatId, d.chatId)
        assertEquals(ChatKind.GROUP, d.kind)
        assertEquals(ChatAccess.PRIVATE, d.access)
        assertEquals("On-Device Room", d.title)
        assertArrayEquals(identity.copySignPublic(), d.signingPublic)

        // A wrong community key is a typed rejection on-device (no crash).
        assertTrue(crypto.openDescriptor(sealed, communityKey(seed = 9)) is ChatParseResult.Rejected)
    }

    // published_chat_entry_uses_keystore_identity — publishing uses the identity's signing key; the
    // published entry carries only the PUBLIC key (the secret never enters the entry). The
    // Keystore-wrapped store/load itself is covered by IdentityStoreInstrumentedTest; here we assert the
    // entry's content discipline on the device backend.
    @Test
    fun published_chat_entry_carries_only_public_key() {
        val identity = identityCrypto().generateIdentity()
        val key = communityKey()
        val crypto = chatDirectoryCrypto()
        val sealed =
            crypto.sealDescriptor(
                chatId = "chat-x".toByteArray(),
                kind = ChatKind.GROUP,
                access = ChatAccess.PUBLIC,
                title = "Room",
                signingPublic = identity.copySignPublic(),
                versionCounter = 1,
                signingSecret = identity.copySignSecret(),
                communityKey = key,
            )
        // The 64-byte signing SECRET key never appears in the sealed entry bytes.
        assertFalse("signing secret must never enter the entry", containsSub(sealed, identity.copySignSecret()))
        // After opening, the recovered key is the PUBLIC signing key (32 bytes).
        val d = (crypto.openDescriptor(sealed, key) as ChatParseResult.Parsed).descriptor
        assertEquals(32, d.signingPublic.size)
    }

    private fun containsSub(
        haystack: ByteArray,
        needle: ByteArray,
    ): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }
}
