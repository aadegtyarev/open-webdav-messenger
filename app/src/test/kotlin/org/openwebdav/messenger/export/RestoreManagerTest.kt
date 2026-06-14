package org.openwebdav.messenger.export

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.crypto.CryptoTestSupport
import org.openwebdav.messenger.identity.IdentityLoadResult
import java.util.Base64

/**
 * JVM unit tests for [RestoreManager] — restore-specific edge cases: no identity in payload,
 * empty chat keys, partial payload validation, store not overwritten on failure.
 */
class RestoreManagerTest {
    private val native = ExportTestSupport.native()

    private fun newRestoreManager(
        cc: ExportableConnectionConfigStore = ExportTestSupport.inMemoryConnectionConfigStore(),
        ck: ExportableCommunityKeyStore = ExportTestSupport.inMemoryCommunityKeyStore(),
        ch: ExportableChatKeyStore = ExportTestSupport.inMemoryChatKeyStore(),
        id: ExportableIdentityStore = ExportTestSupport.inMemoryIdentityStore(),
    ): RestoreManager = RestoreManager(native, cc, ck, ch, id)

    private fun newExportManager(
        cc: ExportableConnectionConfigStore = ExportTestSupport.inMemoryConnectionConfigStore(),
        ck: ExportableCommunityKeyStore = ExportTestSupport.inMemoryCommunityKeyStore(),
        ch: ExportableChatKeyStore = ExportTestSupport.inMemoryChatKeyStore(),
        id: ExportableIdentityStore = ExportTestSupport.inMemoryIdentityStore(),
    ): ExportManager = ExportManager(native, cc, ck, ch, id)

    /** Produce an export blob with the given stores populated. */
    private suspend fun exportBlob(
        cc: ExportableConnectionConfigStore =
            ExportTestSupport.inMemoryConnectionConfigStore().also {
                it.store(ExportTestSupport.sampleConfig())
            },
        ck: ExportableCommunityKeyStore =
            ExportTestSupport.inMemoryCommunityKeyStore().also {
                it.store(CryptoTestSupport.fixedKey(seed = 99))
            },
        ch: ExportableChatKeyStore =
            ExportTestSupport.inMemoryChatKeyStore().also {
                it.store("chat-a", CryptoTestSupport.fixedKey(seed = 10))
            },
        id: ExportableIdentityStore =
            ExportTestSupport.inMemoryIdentityStore().also {
                it.store(ExportTestSupport.freshIdentity())
            },
    ): String {
        val manager = newExportManager(cc, ck, ch, id)
        return (manager.export("test-password-123".toCharArray()) as ExportResult.Ready).blob
    }

    // -- full restore with all stores ----------------------------------------

    @Test
    fun full_restore_populates_all_stores() =
        runTest {
            val blob = exportBlob()
            val ccRestore = ExportTestSupport.inMemoryConnectionConfigStore()
            val ckRestore = ExportTestSupport.inMemoryCommunityKeyStore()
            val chRestore = ExportTestSupport.inMemoryChatKeyStore()
            val idRestore = ExportTestSupport.inMemoryIdentityStore()

            val result =
                newRestoreManager(ccRestore, ckRestore, chRestore, idRestore)
                    .restore(blob, "test-password-123".toCharArray())

            assertEquals(RestoreResult.Restored, result)
            assertTrue("connection config should be restored", ccRestore.load() != null)
            assertTrue("community key should be restored", ckRestore.load() != null)
            assertTrue("chat key should be restored", chRestore.load("chat-a") != null)
            assertTrue("identity should be restored", idRestore.load() is IdentityLoadResult.Loaded)
        }

    // -- restore does not partially populate on failure -----------------------

    @Test
    fun restore_failure_does_not_partially_populate() =
        runTest {
            val blob = exportBlob()
            val ccRestore = ExportTestSupport.inMemoryConnectionConfigStore()
            val idRestore = ExportTestSupport.inMemoryIdentityStore()

            // Use wrong password — stores should remain empty.
            newRestoreManager(ccRestore, id = idRestore)
                .restore(blob, "wrong-password".toCharArray())

            assertEquals("connection config must not be populated on failure", null, ccRestore.load())
            assertEquals("identity must not be populated on failure", IdentityLoadResult.None, idRestore.load())
        }

    // -- empty base64 blob ---------------------------------------------------

    @Test
    fun empty_blob_rejected() =
        runTest {
            val result = newRestoreManager().restore("", "strong-password".toCharArray())
            // Empty string → Base64 decode fails → BadFormat (not WeakPassword because pw is long enough)
            assertEquals(RestoreResult.BadFormat, result)
        }

    // -- oversized / random data ----------------------------------------------

    @Test
    fun random_bytes_rejected() =
        runTest {
            val randomB64 = Base64.getEncoder().encodeToString(ByteArray(1024) { it.toByte() })
            val result = newRestoreManager().restore(randomB64, "strong-password".toCharArray())
            // Wrong magic → BadFormat (or AEAD rejection → WrongPasswordOrTampered)
            assertTrue(result is RestoreResult.BadFormat || result is RestoreResult.WrongPasswordOrTampered)
        }

    // -- too-short blob -------------------------------------------------------

    @Test
    fun too_short_blob_rejected() =
        runTest {
            // A blob shorter than MAGIC+SALT+NONCE+tag → BadFormat
            val short = ByteArray(10) { 0x41.toByte() }
            val shortB64 = Base64.getEncoder().encodeToString(short)
            val result = newRestoreManager().restore(shortB64, "strong-password".toCharArray())
            assertEquals(RestoreResult.BadFormat, result)
        }
}
