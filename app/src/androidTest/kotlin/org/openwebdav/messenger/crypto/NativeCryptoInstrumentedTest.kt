package org.openwebdav.messenger.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented crypto tests — run on the connected device (5c3ff0, arm64) via
 * `./gradlew connectedAndroidTest`. These exercise the REAL lazysodium-android native `.so` for the
 * device ABI through [CryptoFactory] (`docs/stack-notes.md` Crypto: a missing ABI =
 * UnsatisfiedLinkError, only catchable on a device — not the JVM).
 */
@RunWith(AndroidJUnit4::class)
class NativeCryptoInstrumentedTest {
    private val factory = CryptoFactory()

    // native_lib_loads — lazysodium-android loads on the device ABI with no UnsatisfiedLinkError.
    // Source: https://github.com/terl/lazysodium-android
    @Test
    fun native_lib_loads() {
        // Constructing the factory already initialized SodiumAndroid; a randomBytes call proves the
        // native binding works (would throw UnsatisfiedLinkError on a missing ABI).
        val key = factory.keySources().newRandomKey()
        assertEquals(ChatKey.KEY_BYTES, key.export().size)
    }

    // native_aead_roundtrip — real XChaCha20-Poly1305 seal/open on-device.
    @Test
    fun native_aead_roundtrip() {
        val aead = factory.aead()
        val key = factory.keySources().newRandomKey()
        val header = byteArrayOf(0x4F, 0x57, 0x44, 0x4D, 0x01, 0x00, 0x00, 0x00)
        val plaintext = "on-device message".toByteArray()
        val blob = aead.seal(key, header, plaintext)
        val opened = aead.open(key, header, blob)
        assertTrue(opened is OpenResult.Opened)
        assertArrayEquals(plaintext, (opened as OpenResult.Opened).bytes)
        // Wrong key still rejects on-device.
        assertEquals(OpenResult.Rejected, aead.open(factory.keySources().newRandomKey(), header, blob))
    }

    // argon2id_on_device_deterministic — Argon2id runs on-device, deterministic for the same inputs,
    // and completes within a reasonable bound for the INTERACTIVE preset.
    // Source: https://doc.libsodium.org/password_hashing/default_phf
    @Test
    fun argon2id_on_device_deterministic() {
        val sources = factory.keySources()
        val start = System.currentTimeMillis()
        val k1 = runBlocking { sources.keyFromPassphrase("device-pass".toCharArray(), "chat-x") }
        val elapsed = System.currentTimeMillis() - start
        val k2 = runBlocking { sources.keyFromPassphrase("device-pass".toCharArray(), "chat-x") }
        assertArrayEquals(k1.export(), k2.export())
        // INTERACTIVE preset should finish well under 10s even on a mid-range device.
        assertTrue("Argon2id INTERACTIVE took ${elapsed}ms (expected < 10000ms)", elapsed < 10_000)
        // Different chat-id → different key (deterministic salt from chat-id).
        val k3 = runBlocking { sources.keyFromPassphrase("device-pass".toCharArray(), "chat-y") }
        assertFalse(k1.export().contentEquals(k3.export()))
    }
}
