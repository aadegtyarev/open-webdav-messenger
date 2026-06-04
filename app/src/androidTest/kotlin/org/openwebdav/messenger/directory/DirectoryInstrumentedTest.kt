package org.openwebdav.messenger.directory

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
 * Instrumented directory tests — run on the connected device via `./gradlew connectedAndroidTest`.
 * These exercise the REAL lazysodium-android native `.so` paths the §10 directory uses: the
 * XChaCha20-Poly1305 AEAD seal/open of a directory entry under the community key, and the Ed25519
 * sign/verify of the entry's inner payload, on the device ABI with no `UnsatisfiedLinkError`
 * (`docs/stack-notes.md` Crypto: a missing ABI is only catchable on a device, decision-8 gate).
 *
 * The transport (PROPFIND/GET/PUT) is exercised off-device by the JVM MockWebServer suite; these
 * device tests gate the native crypto paths the directory adds on top of the existing substrates.
 */
@RunWith(AndroidJUnit4::class)
class DirectoryInstrumentedTest {
    // The lazysodium-ANDROID backend (the real bundled native `.so` per ABI) — the same backend
    // DirectoryFactory / IdentityFactory wire on-device. One instance owns the native binding.
    private val native: NativeCrypto = LazySodiumCrypto(LazySodiumAndroid(SodiumAndroid()))

    private fun identityCrypto(): IdentityCrypto = IdentityCrypto(native)

    private fun directoryCrypto(): DirectoryCrypto = DirectoryCrypto.create(MessageCrypto(Aead(native)), identityCrypto())

    private fun communityKey(seed: Byte = 7): ChatKey = ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { seed })

    // directory_native_seal_sign_roundtrip — real lazysodium-android AEAD-seal + Ed25519-sign + verify
    // of a directory entry on the device ABI with no UnsatisfiedLinkError.
    // Source: https://github.com/terl/lazysodium-android
    @Test
    fun directory_native_seal_sign_roundtrip() {
        val identity = identityCrypto().generateIdentity()
        val key = communityKey()
        val crypto = directoryCrypto()

        val sealed =
            crypto.sealEntry(
                displayName = "On-Device Member",
                signingPublic = identity.copySignPublic(),
                boxPublic = identity.copyBoxPublic(),
                versionCounter = 1,
                signingSecret = identity.copySignSecret(),
                communityKey = key,
            )
        val parsed = crypto.openEntry(sealed, key)
        assertTrue(parsed is DirectoryParseResult.Parsed)
        val entry = (parsed as DirectoryParseResult.Parsed).entry
        assertEquals("On-Device Member", entry.displayName)
        assertArrayEquals(identity.copySignPublic(), entry.signingPublic)
        assertArrayEquals(identity.copyBoxPublic(), entry.boxPublic)

        // A wrong community key is a typed rejection on-device (no crash).
        assertTrue(crypto.openEntry(sealed, communityKey(seed = 9)) is DirectoryParseResult.Rejected)
    }

    // published_entry_uses_keystore_identity — publishing uses the identity's signing key; the published
    // entry carries only the PUBLIC keys (the secret never enters the entry). The Keystore-wrapped
    // store/load itself is covered by IdentityStoreInstrumentedTest; here we assert the entry's content
    // discipline on the device backend.
    @Test
    fun published_entry_carries_only_public_keys() {
        val identity = identityCrypto().generateIdentity()
        val key = communityKey()
        val crypto = directoryCrypto()
        val sealed =
            crypto.sealEntry(
                displayName = "Member",
                signingPublic = identity.copySignPublic(),
                boxPublic = identity.copyBoxPublic(),
                versionCounter = 1,
                signingSecret = identity.copySignSecret(),
                communityKey = key,
            )
        // The 64-byte signing SECRET key never appears in the sealed entry bytes.
        assertFalse("signing secret must never enter the entry", containsSub(sealed, identity.copySignSecret()))
        // After opening, the recovered keys are the PUBLIC keys (32 bytes each).
        val entry = (crypto.openEntry(sealed, key) as DirectoryParseResult.Parsed).entry
        assertEquals(32, entry.signingPublic.size)
        assertEquals(32, entry.boxPublic.size)
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
