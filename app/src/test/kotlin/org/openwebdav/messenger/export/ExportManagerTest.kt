package org.openwebdav.messenger.export

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.crypto.CryptoTestSupport
import org.openwebdav.messenger.identity.IdentityLoadResult
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JVM unit tests for [ExportManager] — encrypt/decrypt roundtrip via [RestoreManager],
 * bad password, weak password, empty stores, KDF dispatcher offloading.
 */
class ExportManagerTest {
    private val native = ExportTestSupport.native()
    private val identityCrypto = ExportTestSupport.identityCrypto()

    private fun newExportManager(
        cc: ExportableConnectionConfigStore = ExportTestSupport.inMemoryConnectionConfigStore(),
        ck: ExportableCommunityKeyStore = ExportTestSupport.inMemoryCommunityKeyStore(),
        ch: ExportableChatKeyStore = ExportTestSupport.inMemoryChatKeyStore(),
        id: ExportableIdentityStore = ExportTestSupport.inMemoryIdentityStore(),
    ): ExportManager = ExportManager(native, cc, ck, ch, id)

    private fun newRestoreManager(
        cc: ExportableConnectionConfigStore = ExportTestSupport.inMemoryConnectionConfigStore(),
        ck: ExportableCommunityKeyStore = ExportTestSupport.inMemoryCommunityKeyStore(),
        ch: ExportableChatKeyStore = ExportTestSupport.inMemoryChatKeyStore(),
        id: ExportableIdentityStore = ExportTestSupport.inMemoryIdentityStore(),
    ): RestoreManager = RestoreManager(native, cc, ck, ch, id)

    // -- basic roundtrip ------------------------------------------------------

    @Test
    fun export_then_restore_roundtrips() =
        runTest {
            // Populate all stores with known data.
            val ccStore = ExportTestSupport.inMemoryConnectionConfigStore()
            val ckStore = ExportTestSupport.inMemoryCommunityKeyStore()
            val chStore = ExportTestSupport.inMemoryChatKeyStore()
            val idStore = ExportTestSupport.inMemoryIdentityStore()

            val originalConfig = ExportTestSupport.sampleConfig()
            val originalCommunityKey = CryptoTestSupport.fixedKey(seed = 42)
            val originalChatKey1 = CryptoTestSupport.fixedKey(seed = 1)
            val originalChatKey2 = CryptoTestSupport.fixedKey(seed = 2)
            val originalIdentity = ExportTestSupport.freshIdentity()

            ccStore.store(originalConfig)
            ckStore.store(originalCommunityKey)
            chStore.store("chat-1", originalChatKey1)
            chStore.store("chat-2", originalChatKey2)
            idStore.store(originalIdentity)

            val exportManager = newExportManager(ccStore, ckStore, chStore, idStore)

            val result = exportManager.export("strong-password-123".toCharArray())
            assertTrue("export should succeed", result is ExportResult.Ready)
            val blob = (result as ExportResult.Ready).blob

            // Restore into empty stores.
            val ccRestore = ExportTestSupport.inMemoryConnectionConfigStore()
            val ckRestore = ExportTestSupport.inMemoryCommunityKeyStore()
            val chRestore = ExportTestSupport.inMemoryChatKeyStore()
            val idRestore = ExportTestSupport.inMemoryIdentityStore()
            val restoreManager = newRestoreManager(ccRestore, ckRestore, chRestore, idRestore)

            val restoreResult = restoreManager.restore(blob, "strong-password-123".toCharArray())
            assertEquals(RestoreResult.Restored, restoreResult)

            // Verify all stores were populated correctly.
            val restoredConfig = ccRestore.load()
            assertEquals(originalConfig, restoredConfig)

            val restoredCommunityKey = ckRestore.load()
            assertTrue(originalCommunityKey.export().contentEquals(restoredCommunityKey!!.export()))

            val restoredChat1 = chRestore.load("chat-1")
            assertTrue(originalChatKey1.export().contentEquals(restoredChat1!!.export()))
            val restoredChat2 = chRestore.load("chat-2")
            assertTrue(originalChatKey2.export().contentEquals(restoredChat2!!.export()))

            val restoredIdentity =
                when (val r = idRestore.load()) {
                    is IdentityLoadResult.Loaded -> r.identity
                    else -> error("identity not restored")
                }
            assertEquals(originalIdentity.copySignPublic().contentToString(), restoredIdentity.copySignPublic().contentToString())
            assertEquals(originalIdentity.copyBoxPublic().contentToString(), restoredIdentity.copyBoxPublic().contentToString())
        }

    // -- wrong password -------------------------------------------------------

    @Test
    fun wrong_password_fails() =
        runTest {
            val idStore = ExportTestSupport.inMemoryIdentityStore()
            idStore.store(ExportTestSupport.freshIdentity())

            val exportManager = newExportManager(id = idStore)
            val result = exportManager.export("correct-password".toCharArray())
            val blob = (result as ExportResult.Ready).blob

            val restoreManager = newRestoreManager(id = ExportTestSupport.inMemoryIdentityStore())
            val restoreResult = restoreManager.restore(blob, "wrong-password".toCharArray())

            assertEquals(RestoreResult.WrongPasswordOrTampered, restoreResult)
        }

    // -- weak password --------------------------------------------------------

    @Test
    fun weak_password_rejected_on_export() =
        runTest {
            val exportManager = newExportManager()
            val result = exportManager.export("short".toCharArray())
            assertEquals(ExportResult.WeakPassword, result)
        }

    @Test
    fun weak_password_rejected_on_restore() =
        runTest {
            // Create a minimal export first.
            val idStore = ExportTestSupport.inMemoryIdentityStore()
            idStore.store(ExportTestSupport.freshIdentity())
            val exportManager = newExportManager(id = idStore)
            val blob = (exportManager.export("strong-password".toCharArray()) as ExportResult.Ready).blob

            val restoreManager = newRestoreManager()
            val result = restoreManager.restore(blob, "short".toCharArray())
            assertEquals(RestoreResult.WeakPassword, result)
        }

    // -- tampered blob --------------------------------------------------------

    @Test
    fun tampered_blob_rejected() =
        runTest {
            val idStore = ExportTestSupport.inMemoryIdentityStore()
            idStore.store(ExportTestSupport.freshIdentity())
            val exportManager = newExportManager(id = idStore)

            val result = exportManager.export("correct-password".toCharArray())
            val blob = (result as ExportResult.Ready).blob

            // Flip a byte in the base64 blob.
            val tampered =
                blob.toCharArray().apply {
                    val mid = size / 2
                    this[mid] = if (this[mid] == 'A') 'B' else 'A'
                }.concatToString()

            val restoreManager = newRestoreManager(id = ExportTestSupport.inMemoryIdentityStore())
            // The tampering either breaks base64 or causes AEAD authentication failure.
            val restoreResult = restoreManager.restore(tampered, "correct-password".toCharArray())
            assertTrue(
                restoreResult is RestoreResult.BadFormat || restoreResult is RestoreResult.WrongPasswordOrTampered,
            )
        }

    // -- tampered ciphertext (modify a byte in the decoded blob) ---------------

    @Test
    fun tampered_ciphertext_rejected() =
        runTest {
            val idStore = ExportTestSupport.inMemoryIdentityStore()
            idStore.store(ExportTestSupport.freshIdentity())
            val exportManager = newExportManager(id = idStore)

            val result = exportManager.export("correct-password".toCharArray())
            val blob = (result as ExportResult.Ready).blob

            // Decode, flip a byte in the ciphertext, re-encode.
            val decoded = Base64.getDecoder().decode(blob)
            val minCiphertextStart = ExportManager.MAGIC.size + ExportManager.SALT_BYTES + ExportManager.NONCE_BYTES
            if (decoded.size > minCiphertextStart) {
                decoded[decoded.size - 1] = (decoded[decoded.size - 1] + 1).toByte()
            }
            val tamperedBlob = Base64.getEncoder().encodeToString(decoded)

            val restoreManager = newRestoreManager(id = ExportTestSupport.inMemoryIdentityStore())
            val restoreResult = restoreManager.restore(tamperedBlob, "correct-password".toCharArray())
            assertEquals(RestoreResult.WrongPasswordOrTampered, restoreResult)
        }

    // -- empty stores ---------------------------------------------------------

    @Test
    fun empty_stores_export_succeeds() =
        runTest {
            // Identity is required for restore, but export collects whatever is present.
            val idStore = ExportTestSupport.inMemoryIdentityStore()
            idStore.store(ExportTestSupport.freshIdentity())

            val exportManager = newExportManager(id = idStore)
            val result = exportManager.export("strong-password".toCharArray())
            assertTrue("export should succeed with only identity", result is ExportResult.Ready)
        }

    @Test
    fun empty_stores_roundtrip() =
        runTest {
            val idStore = ExportTestSupport.inMemoryIdentityStore()
            val originalIdentity = ExportTestSupport.freshIdentity()
            idStore.store(originalIdentity)

            val exportManager = newExportManager(id = idStore)
            val result = exportManager.export("strong-password".toCharArray())
            val blob = (result as ExportResult.Ready).blob

            // Restore into fresh empty stores.
            val idRestore = ExportTestSupport.inMemoryIdentityStore()
            val restoreManager = newRestoreManager(id = idRestore)
            val restoreResult = restoreManager.restore(blob, "strong-password".toCharArray())
            assertEquals(RestoreResult.Restored, restoreResult)

            val restoredIdentity =
                when (val r = idRestore.load()) {
                    is IdentityLoadResult.Loaded -> r.identity
                    else -> error("identity not restored")
                }
            assertTrue(originalIdentity.copySignPublic().contentEquals(restoredIdentity.copySignPublic()))
            assertTrue(originalIdentity.copyBoxPublic().contentEquals(restoredIdentity.copyBoxPublic()))
        }

    // -- bad format -----------------------------------------------------------

    @Test
    fun garbage_blob_rejected() =
        runTest {
            val restoreManager = newRestoreManager()
            val result = restoreManager.restore("not-a-valid-export-blob", "strong-password".toCharArray())
            assertEquals(RestoreResult.BadFormat, result)
        }

    @Test
    fun wrong_magic_header_rejected() =
        runTest {
            // Create a valid export, then replace the magic header with garbage.
            val idStore = ExportTestSupport.inMemoryIdentityStore()
            idStore.store(ExportTestSupport.freshIdentity())
            val exportManager = newExportManager(id = idStore)
            val blob = (exportManager.export("correct-password".toCharArray()) as ExportResult.Ready).blob

            val decoded = Base64.getDecoder().decode(blob)
            // Corrupt the magic header.
            if (decoded.isNotEmpty()) {
                decoded[0] = 0x00.toByte()
            }
            val tamperedBlob = Base64.getEncoder().encodeToString(decoded)

            val restoreManager = newRestoreManager(id = ExportTestSupport.inMemoryIdentityStore())
            val result = restoreManager.restore(tamperedBlob, "correct-password".toCharArray())
            assertEquals(RestoreResult.BadFormat, result)
        }

    // -- export blob is non-deterministic (different nonce each time) ----------

    @Test
    fun export_is_nondeterministic() =
        runTest {
            val idStore = ExportTestSupport.inMemoryIdentityStore()
            idStore.store(ExportTestSupport.freshIdentity())
            val exportManager = newExportManager(id = idStore)

            val blob1 = (exportManager.export("correct-password".toCharArray()) as ExportResult.Ready).blob
            val blob2 = (exportManager.export("correct-password".toCharArray()) as ExportResult.Ready).blob

            // Two exports of the same data with the same password must differ (random nonce/salt).
            assertNotEquals(blob1, blob2)
        }

    // -- passphrase zeroization (export side) ---------------------------------

    @Test
    fun passphrase_is_zeroized_after_export() =
        runTest {
            val idStore = ExportTestSupport.inMemoryIdentityStore()
            idStore.store(ExportTestSupport.freshIdentity())
            val exportManager = newExportManager(id = idStore)

            val pw = "test-password-123".toCharArray()
            exportManager.export(pw)
            assertTrue("passphrase must be wiped after export", pw.all { it == ' ' })
        }

    // -- passphrase zeroization (restore side) --------------------------------

    @Test
    fun passphrase_is_zeroized_after_restore() =
        runTest {
            val idStore = ExportTestSupport.inMemoryIdentityStore()
            idStore.store(ExportTestSupport.freshIdentity())
            val exportManager = newExportManager(id = idStore)
            val blob = (exportManager.export("correct-password".toCharArray()) as ExportResult.Ready).blob

            val restoreManager = newRestoreManager(id = ExportTestSupport.inMemoryIdentityStore())
            val pw = "correct-password".toCharArray()
            restoreManager.restore(blob, pw)
            assertTrue("passphrase must be wiped after restore", pw.all { it == ' ' })
        }

    // -- KDF runs off calling thread -----------------------------------------

    @Test
    fun kdf_runs_off_calling_thread() {
        val dispatched = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "test-kdf-export") }
        try {
            val tracking =
                object : kotlinx.coroutines.CoroutineDispatcher() {
                    override fun dispatch(
                        context: kotlin.coroutines.CoroutineContext,
                        block: Runnable,
                    ) {
                        dispatched.set(true)
                        executor.execute(block)
                    }
                }
            val idStore = ExportTestSupport.inMemoryIdentityStore()
            idStore.store(ExportTestSupport.freshIdentity())
            val exportManager =
                ExportManager(
                    native,
                    ExportTestSupport.inMemoryConnectionConfigStore(),
                    ExportTestSupport.inMemoryCommunityKeyStore(),
                    ExportTestSupport.inMemoryChatKeyStore(),
                    idStore,
                    tracking,
                )

            runBlocking {
                exportManager.export("test-password-123".toCharArray())
            }
            assertTrue("Argon2id KDF did not dispatch onto injected IO dispatcher", dispatched.get())
        } finally {
            executor.shutdownNow()
        }
    }
}
