package org.openwebdav.messenger.keystore

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Android Keystore tests for [HistoryKeyStore] — run on the connected device via
 * `./gradlew connectedAndroidTest`. Keystore is device-backed (TEE/StrongBox), so wrap/unwrap cannot
 * be exercised on the JVM (`docs/stack-notes.md` → Android Keystore: instrumented-only).
 */
@RunWith(AndroidJUnit4::class)
class HistoryKeyStoreInstrumentedTest {
    private lateinit var store: HistoryKeyStore

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        store = HistoryKeyStore(context)
        store.remove()
    }

    // history_keystore_wrap_unwrap_roundtrip — a key generated and Keystore-wrapped, then unwrapped on
    // a subsequent getOrCreateKey, returns the SAME 32-byte key; the raw key is NOT in the on-disk blob.
    @Test
    fun history_keystore_wrap_unwrap_roundtrip() {
        val first = store.getOrCreateKey()
        try {
            assertEquals(32, first.size)

            // Second call unwraps the stored key, not a fresh one.
            val second = store.getOrCreateKey()
            try {
                assertArrayEquals(first, second)
            } finally {
                second.fill(0)
            }

            // The on-disk blob is Keystore-wrapped ciphertext — must NOT contain raw key bytes.
            val onDisk = store.rawStoredBlob()
            assertTrue(onDisk != null)
            assertFalse(
                "raw key must not appear in wrapped on-disk blob",
                containsSubsequence(onDisk!!, first),
            )
        } finally {
            first.fill(0)
        }
    }

    // generate_once_then_unwrap — removing and re-creating the store yields a FRESH key each time
    // (distinct from the first), proving regeneration works and does not return the old key.
    @Test
    fun generate_once_then_unwrap() {
        val first = store.getOrCreateKey()
        store.remove()
        val second = store.getOrCreateKey()
        try {
            assertFalse("regenerated key must differ from the deleted one", first.contentEquals(second))
        } finally {
            first.fill(0)
            second.fill(0)
        }
    }

    // has_reflects_stored_key — after getOrCreateKey, has() is true; after remove(), has() is false.
    @Test
    fun has_reflects_stored_key() {
        assertFalse(store.has())
        val key = store.getOrCreateKey()
        try {
            assertTrue(store.has())
        } finally {
            key.fill(0)
        }
        store.remove()
        assertFalse(store.has())
    }

    // remove_deletes_stored_key — after remove(), getOrCreateKey generates a fresh key (the old blob
    // is gone).
    @Test
    fun remove_deletes_stored_key() {
        val first = store.getOrCreateKey()
        store.remove()
        val second = store.getOrCreateKey()
        try {
            assertFalse("after remove, getOrCreateKey must generate a fresh key", first.contentEquals(second))
        } finally {
            first.fill(0)
            second.fill(0)
        }
    }

    private fun assertEquals(
        expected: Int,
        actual: Int,
    ) {
        org.junit.Assert.assertEquals(expected, actual)
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
