package org.openwebdav.messenger.keystore

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.CryptoFactory

/**
 * Instrumented Android Keystore tests — run on the connected device via `./gradlew connectedAndroidTest`.
 * Keystore is device-backed (TEE/StrongBox), so wrap/unwrap cannot be exercised on the JVM
 * (`docs/stack-notes.md` → Android Keystore: instrumented-only).
 */
@RunWith(AndroidJUnit4::class)
class ChatKeyStoreInstrumentedTest {
    private lateinit var store: ChatKeyStore
    private val chatId = "keystore-test-chat"

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // The store shares CryptoFactory's NativeCrypto so the key-file token is BLAKE2b(chat-id)
        // (collision-resistant), not the old polynomial fold that could overwrite a sibling chat's key.
        store = CryptoFactory().chatKeyStore(context)
        store.remove(chatId)
    }

    // keystore_wrap_unwrap_roundtrip — a key wrapped via Android Keystore and unwrapped yields the
    // same key; the raw key is NOT retrievable in plaintext from storage.
    // Source: https://developer.android.com/privacy-and-security/keystore
    @Test
    fun keystore_wrap_unwrap_roundtrip() {
        val raw = ByteArray(ChatKey.KEY_BYTES) { (it * 3 + 1).toByte() }
        val key = ChatKey.fromBytes(raw)

        store.store(chatId, key)
        val loaded = store.load(chatId)
        assertTrue(loaded != null)
        assertArrayEquals(raw, loaded!!.export())

        // The on-disk blob is the Keystore-wrapped ciphertext — it must NOT contain the raw key bytes.
        val onDisk = store.rawStoredBlob(chatId)
        assertTrue(onDisk != null)
        assertFalse("raw key must not appear in wrapped on-disk blob", containsSubsequence(onDisk!!, raw))
    }

    @Test
    fun load_absent_chat_returns_null() {
        assertNull(store.load("no-such-chat"))
    }

    // two_chat_ids_do_not_collide — distinct chat-ids must map to distinct key files and round-trip
    // independently. Guards against the old polynomial-fold filename hash, where two chat-ids could
    // collide and silently overwrite each other's wrapped key (availability / data-loss).
    @Test
    fun two_chat_ids_do_not_collide() {
        val chatA = "chat-alpha"
        val chatB = "chat-bravo"
        store.remove(chatA)
        store.remove(chatB)

        val rawA = ByteArray(ChatKey.KEY_BYTES) { (it + 1).toByte() }
        val rawB = ByteArray(ChatKey.KEY_BYTES) { (it + 100).toByte() }

        store.store(chatA, ChatKey.fromBytes(rawA))
        store.store(chatB, ChatKey.fromBytes(rawB))

        // Storing B must NOT have overwritten A's key (would happen on a filename collision).
        assertArrayEquals(rawA, store.load(chatA)!!.export())
        assertArrayEquals(rawB, store.load(chatB)!!.export())

        // They are physically distinct files: distinct wrapped blobs on disk.
        val blobA = store.rawStoredBlob(chatA)!!
        val blobB = store.rawStoredBlob(chatB)!!
        assertFalse("two chat-ids must not share an on-disk key file", blobA.contentEquals(blobB))

        // Removing one leaves the other intact (independent files).
        store.remove(chatA)
        assertNull(store.load(chatA))
        assertArrayEquals(rawB, store.load(chatB)!!.export())

        store.remove(chatB)
    }

    @Test
    fun remove_deletes_stored_key() {
        store.store(chatId, ChatKey.fromBytes(ByteArray(ChatKey.KEY_BYTES) { 5 }))
        assertTrue(store.has(chatId))
        store.remove(chatId)
        assertFalse(store.has(chatId))
        assertNull(store.load(chatId))
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
