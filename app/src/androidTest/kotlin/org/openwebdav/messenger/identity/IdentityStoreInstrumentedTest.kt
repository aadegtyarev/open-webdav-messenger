package org.openwebdav.messenger.identity

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.CryptoFactory
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Instrumented Android Keystore tests for [IdentityStore] — run on the connected device via
 * `./gradlew connectedAndroidTest`. Keystore is device-backed (TEE/StrongBox), so wrap/unwrap cannot
 * be exercised on the JVM (`docs/stack-notes.md` → Android Keystore: instrumented-only).
 */
@RunWith(AndroidJUnit4::class)
class IdentityStoreInstrumentedTest {
    private lateinit var store: IdentityStore

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        store = IdentityFactory().identityStore(context)
        store.remove()
    }

    // identity_keystore_wrap_unwrap — identity secret keys wrapped via Android Keystore and unwrapped
    // yield the same keys; the raw secret is NOT retrievable in plaintext from storage.
    // Source: https://developer.android.com/privacy-and-security/keystore
    @Test
    fun identity_keystore_wrap_unwrap() {
        val identity = IdentityFactory().identityCrypto().generateIdentity()
        store.store(identity)
        val loaded = loadedIdentity()
        assertArrayEquals(identity.copySignSecret(), loaded.copySignSecret())
        assertArrayEquals(identity.copyBoxSecret(), loaded.copyBoxSecret())
        assertArrayEquals(identity.copySignPublic(), loaded.copySignPublic())
        assertArrayEquals(identity.copyBoxPublic(), loaded.copyBoxPublic())

        // The on-disk blob is Keystore-wrapped ciphertext — it must NOT contain the raw secret keys.
        val onDisk = store.rawStoredBlob()
        assertTrue(onDisk != null)
        assertFalse(
            "Ed25519 sk must not appear in wrapped on-disk blob",
            containsSubsequence(onDisk!!, identity.copySignSecret()),
        )
        assertFalse(
            "X25519 sk must not appear in wrapped on-disk blob",
            containsSubsequence(onDisk, identity.copyBoxSecret()),
        )
    }

    // identity_persists_across_load — store then loadOrCreate returns the SAME identity (generate-once),
    // not a fresh one.
    @Test
    fun identity_persists_across_load() {
        val first = runBlocking { store.loadOrCreate() }
        val second = runBlocking { store.loadOrCreate() }
        assertArrayEquals(first.copySignPublic(), second.copySignPublic())
        assertArrayEquals(first.copySignSecret(), second.copySignSecret())
        assertArrayEquals(first.copyBoxPublic(), second.copyBoxPublic())
        assertArrayEquals(first.copyBoxSecret(), second.copyBoxSecret())
        // load() after first-run generation returns the same identity.
        assertArrayEquals(first.copyBoxPublic(), loadedIdentity().copyBoxPublic())
    }

    // identity_store_does_not_disturb_chat_keys — storing/loading an identity leaves a previously
    // stored chat key intact (distinct alias + distinct file), and vice versa.
    @Test
    fun identity_store_does_not_disturb_chat_keys() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val chatStore = CryptoFactory().chatKeyStore(context)
        val chatId = "identity-coexist-chat"
        val rawChatKey = ByteArray(ChatKey.KEY_BYTES) { (it + 9).toByte() }
        chatStore.remove(chatId)
        chatStore.store(chatId, ChatKey.fromBytes(rawChatKey))

        // Store an identity — must not disturb the chat key.
        val identity = IdentityFactory().identityCrypto().generateIdentity()
        store.store(identity)
        assertArrayEquals(rawChatKey, chatStore.load(chatId)!!.export())

        // Load the identity back — chat key still intact, identity still correct.
        assertArrayEquals(identity.copyBoxSecret(), loadedIdentity().copyBoxSecret())
        assertArrayEquals(rawChatKey, chatStore.load(chatId)!!.export())

        // Removing the identity does not remove the chat key.
        store.remove()
        assertTrue(store.load() is IdentityLoadResult.None)
        assertArrayEquals(rawChatKey, chatStore.load(chatId)!!.export())

        chatStore.remove(chatId)
    }

    // atomic_write_partial_blob_surfaces_unrecoverable — a pre-existing truncated/garbage identity file
    // (the shape an interrupted, non-atomic write would leave) does NOT cause silent regeneration or an
    // uncaught crash: load() returns Unrecoverable and loadOrCreate() throws (never generates a new one).
    // The atomic write (fix 2) prevents this state going forward; this proves the load path is safe even
    // if it is ever observed (e.g. a blob written by an older build, or storage corruption).
    @Test
    fun atomic_write_partial_blob_surfaces_unrecoverable() {
        // A valid-length but undecryptable blob: right size to pass the IV-length gate, wrong GCM tag.
        store.writeRawBlobForTest(ByteArray(64) { (it * 7 + 3).toByte() })
        assertTrue(store.load() is IdentityLoadResult.Unrecoverable)
        assertThrows(IdentityUnrecoverableException::class.java) { runBlocking { store.loadOrCreate() } }
        // It must NOT have regenerated over the corrupt file (silent account loss). The file is still
        // the corrupt one we wrote; loadOrCreate did not overwrite it with a fresh identity.
        assertTrue(store.load() is IdentityLoadResult.Unrecoverable)
    }

    // corrupt_blob_load_returns_unrecoverable_not_none — a too-short (truncated) blob, the shape a crash
    // mid-write left BEFORE the atomic-write fix, must surface as Unrecoverable (the file exists), never
    // None (which would let loadOrCreate silently generate a new identity = account loss).
    @Test
    fun corrupt_blob_load_returns_unrecoverable_not_none() {
        store.writeRawBlobForTest(byteArrayOf(1, 2, 3)) // shorter than the 12-byte IV
        assertTrue(store.load() is IdentityLoadResult.Unrecoverable)
        assertFalse(store.load() is IdentityLoadResult.None)
        assertThrows(IdentityUnrecoverableException::class.java) { runBlocking { store.loadOrCreate() } }
    }

    // atomic_write_no_partial_after_store — a normal store() lands a fully-recoverable blob (the atomic
    // temp-then-rename never exposes a partial), and load() reads it back as Loaded.
    @Test
    fun atomic_write_no_partial_after_store() {
        val identity = IdentityFactory().identityCrypto().generateIdentity()
        store.store(identity)
        val loaded = loadedIdentity()
        assertArrayEquals(identity.copySignPublic(), loaded.copySignPublic())
    }

    // concurrent_load_or_create_yields_one_identity — two concurrent first-run loadOrCreate calls produce
    // ONE identity (the in-process lock + double-check). The cross-process FileLock in IdentityStore
    // extends this guarantee across separate android:process processes (a WorkManager/foreground process
    // racing the UI process) — not exercisable from a single instrumented process, see generateOnce().
    @Test
    fun concurrent_load_or_create_yields_one_identity() {
        store.remove()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val task = Callable { runBlocking { store.loadOrCreate() } }
            val a = pool.submit(task)
            val b = pool.submit(task)
            val idA = a.get(30, TimeUnit.SECONDS)
            val idB = b.get(30, TimeUnit.SECONDS)
            // Both calls observe the SAME single identity, not two independently generated ones.
            assertArrayEquals(idA.copySignPublic(), idB.copySignPublic())
            assertArrayEquals(idA.copyBoxSecret(), idB.copyBoxSecret())
            assertArrayEquals(idA.copySignPublic(), loadedIdentity().copySignPublic())
        } finally {
            pool.shutdownNow()
        }
    }

    private fun loadedIdentity(): Identity {
        val result = store.load()
        assertTrue("expected Loaded, was $result", result is IdentityLoadResult.Loaded)
        return (result as IdentityLoadResult.Loaded).identity
    }

    private fun containsSubsequence(
        haystack: ByteArray,
        needle: ByteArray,
    ): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        outer@ for (start in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[start + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }
}
