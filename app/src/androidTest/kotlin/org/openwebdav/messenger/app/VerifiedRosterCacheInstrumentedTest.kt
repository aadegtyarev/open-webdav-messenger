package org.openwebdav.messenger.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.CryptoFactory
import org.openwebdav.messenger.directory.DirectoryEntry
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.identity.IdentityCrypto
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class VerifiedRosterCacheInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun strict_replace_failure_preserves_ciphertext_and_applies_runtime_roster() {
        val standardStore = VerifiedRosterCacheStore(context)
        standardStore.clear()
        val identity = IdentityCrypto(CryptoFactory().nativeCrypto()).generateIdentity()
        val communityKey = ChatKey.fromBytes(ByteArray(32) { 1 })
        val chatKey = ChatKey.fromBytes(ByteArray(32) { 2 })
        val original = cachedRoster("Original", identity, communityKey, chatKey)
        assertTrue(VerifiedRosterCache(standardStore).commit(0, original) { true })
        val file = File(context.filesDir, "verified_roster/rosters.bin")
        file.parentFile!!.listFiles()?.filterNot { it == file }?.forEach(File::delete)
        val oldCiphertext = file.readBytes()
        val failingStore =
            VerifiedRosterCacheStore(context) { _, _ ->
                throw IOException("simulated move failure")
            }
        val fresh = cachedRoster("Fresh", identity, communityKey, chatKey)
        var runtimeReadiness: RecipientReadiness = RecipientReadiness.Ready(listOf("Original"))
        assertTrue(
            VerifiedRosterCache(failingStore).commit(0, fresh) {
                runtimeReadiness = RecipientReadiness.Ready(listOf("Fresh"))
                true
            },
        )
        assertEquals(RecipientReadiness.Ready(listOf("Fresh")), runtimeReadiness)
        assertArrayEquals(oldCiphertext, file.readBytes())
        assertEquals(1, file.parentFile!!.listFiles()!!.size)
        val persisted = VerifiedRosterCache(standardStore).load("community", "chat", communityKey, chatKey, identity)
        assertEquals("Original", persisted!!.entries.single().displayName)
        standardStore.clear()
    }

    private fun cachedRoster(
        name: String,
        identity: Identity,
        communityKey: ChatKey,
        chatKey: ChatKey,
    ): CachedVerifiedRoster {
        val provenance = RosterCacheProvenance.digest("community", "chat", communityKey, chatKey, identity)
        return CachedVerifiedRoster(
            "community",
            "chat",
            provenance,
            listOf(DirectoryEntry(name, ByteArray(32) { 3 }, ByteArray(32) { 4 })),
        )
    }

    @Test
    fun encrypted_cache_survives_store_recreation_and_rejects_corruption() {
        val store = VerifiedRosterCacheStore(context)
        store.clear()
        val native = CryptoFactory().nativeCrypto()
        val identity = IdentityCrypto(native).generateIdentity()
        val communityKey = ChatKey.fromBytes(ByteArray(32) { 1 })
        val chatKey = ChatKey.fromBytes(ByteArray(32) { 2 })
        val persistence = VerifiedRosterCacheStore(context)
        val cache = VerifiedRosterCache(persistence)
        val entry = DirectoryEntry("CacheSecretName", ByteArray(32) { 3 }, ByteArray(32) { 4 })
        val provenance = RosterCacheProvenance.digest("community", "chat", communityKey, chatKey, identity)
        val record = CachedVerifiedRoster("community", "chat", provenance, listOf(entry))
        assertTrue(cache.commit(cache.generation(), record) { true })

        val file = File(context.filesDir, "verified_roster/rosters.bin")
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("CacheSecretName"))
        val restarted = VerifiedRosterCache(VerifiedRosterCacheStore(context))
        assertEquals("CacheSecretName", restarted.load("community", "chat", communityKey, chatKey, identity)!!.entries.single().displayName)
        assertNull(restarted.load("other", "chat", communityKey, chatKey, identity))

        file.writeBytes(ByteArray(RosterCacheCodec.MAX_FILE_BYTES + 29))
        assertNull(VerifiedRosterCacheStore(context).load("community", "chat"))
        store.clear()
    }
}
