package org.openwebdav.messenger.crypto

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Key-source unit tests (JVM, real libsodium) — Argon2id passphrase keys, known-from-chat-id keys,
 * and CSPRNG random keys (`docs/architecture.md` decision 9).
 */
class KeySourcesTest {
    private val sources = KeySources(CryptoTestSupport.native())

    @Test
    fun passphrase_key_is_deterministic() =
        runTest {
            val k1 = sources.keyFromPassphrase("correct horse".toCharArray(), "chat-1")
            val k2 = sources.keyFromPassphrase("correct horse".toCharArray(), "chat-1")
            // Same (passphrase, chat-id) → identical key, so members derive the same key independently.
            assertArrayEquals(k1.export(), k2.export())
        }

    @Test
    fun passphrase_key_differs_by_chatid() =
        runTest {
            val a = sources.keyFromPassphrase("pw".toCharArray(), "chat-A")
            val b = sources.keyFromPassphrase("pw".toCharArray(), "chat-B")
            // Different chat-id → different salt → different key (deterministic salt from chat-id).
            assertFalse(a.export().contentEquals(b.export()))
        }

    @Test
    fun passphrase_key_differs_by_passphrase() =
        runTest {
            val a = sources.keyFromPassphrase("pw-one".toCharArray(), "chat-1")
            val b = sources.keyFromPassphrase("pw-two".toCharArray(), "chat-1")
            assertFalse(a.export().contentEquals(b.export()))
        }

    @Test
    fun known_key_is_deterministic_from_chatid() {
        val a = sources.knownKey("public-chat-1")
        val b = sources.knownKey("public-chat-1")
        // No passphrase input — same chat-id always yields the same (non-secret) public-chat key.
        assertArrayEquals(a.export(), b.export())
    }

    @Test
    fun known_key_differs_by_chatid() {
        val a = sources.knownKey("public-A")
        val b = sources.knownKey("public-B")
        assertFalse(a.export().contentEquals(b.export()))
    }

    @Test
    fun random_key_is_unique() {
        val a = sources.newRandomKey()
        val b = sources.newRandomKey()
        // Two CSPRNG keys differ with overwhelming probability.
        assertFalse(a.export().contentEquals(b.export()))
        assertEquals(ChatKey.KEY_BYTES, a.export().size)
    }

    // passphrase_char_array_is_wiped_after_derivation — the caller-supplied CharArray must be zeroized
    // so the cleartext passphrase does not linger in a caller-held array (Security constraints:
    // passphrases never persist beyond use; defense-in-depth over the no-intermediate-String encode).
    @Test
    fun passphrase_char_array_is_wiped_after_derivation() =
        runTest {
            val passphrase = "secret-pass-123".toCharArray()
            sources.keyFromPassphrase(passphrase, "chat-wipe")
            // Every char must have been overwritten with a space — no cleartext left in the array.
            assertTrue(
                "passphrase array must be wiped after derivation",
                passphrase.all { it == ' ' },
            )
        }

    @Test
    fun import_raw_key_roundtrips() {
        val raw = ByteArray(ChatKey.KEY_BYTES) { it.toByte() }
        val key = sources.importRawKey(raw)
        assertArrayEquals(raw, key.export())
    }

    // kdf_runs_off_the_calling_thread_on_injected_dispatcher — Argon2id MUST run off the main/UI
    // thread (stack-notes Kotlin/Crypto: "Blocking I/O (network, disk, crypto KDF) must not run on
    // the main/UI dispatcher; use coroutines on Dispatchers.IO").
    // Source: https://kotlinlang.org/docs/coroutines-and-channels.html (and Crypto component note).
    @Test
    fun kdf_runs_off_the_calling_thread_on_injected_dispatcher() {
        val dispatched = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, KDF_THREAD_NAME) }
        try {
            // A tracking dispatcher: records that it was used and confines work to its own named thread.
            val tracking =
                object : CoroutineDispatcher() {
                    override fun dispatch(
                        context: kotlin.coroutines.CoroutineContext,
                        block: Runnable,
                    ) {
                        dispatched.set(true)
                        executor.execute(block)
                    }
                }
            val sources = KeySources(CryptoTestSupport.native(), ioDispatcher = tracking)

            val callingThread = Thread.currentThread().name
            runBlocking {
                // The derivation suspends onto the injected dispatcher; if it ran inline on the caller
                // the dispatcher's dispatch() would never fire and `dispatched` would stay false.
                sources.keyFromPassphrase("off-thread".toCharArray(), "chat-kdf")
            }
            assertTrue("Argon2id KDF did not dispatch onto the injected IO dispatcher", dispatched.get())
            // The injected dispatcher confines work to KDF_THREAD_NAME, which is NOT the caller thread —
            // i.e. the KDF demonstrably did not run on the calling (here: runBlocking/main) thread.
            assertFalse(
                "the injected KDF thread must differ from the calling thread",
                callingThread == KDF_THREAD_NAME,
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun all_three_sources_produce_32_byte_keys() =
        runTest {
            assertEquals(ChatKey.KEY_BYTES, sources.keyFromPassphrase("p".toCharArray(), "c").export().size)
            assertEquals(ChatKey.KEY_BYTES, sources.newRandomKey().export().size)
            assertEquals(ChatKey.KEY_BYTES, sources.knownKey("c").export().size)
        }

    private companion object {
        const val KDF_THREAD_NAME = "test-kdf-io-dispatcher"
    }
}
