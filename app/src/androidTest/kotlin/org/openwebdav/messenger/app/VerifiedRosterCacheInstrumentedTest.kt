package org.openwebdav.messenger.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.CryptoFactory
import org.openwebdav.messenger.directory.DirectoryEntry
import org.openwebdav.messenger.identity.IdentityCrypto
import java.io.File

@RunWith(AndroidJUnit4::class)
class VerifiedRosterCacheInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

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
