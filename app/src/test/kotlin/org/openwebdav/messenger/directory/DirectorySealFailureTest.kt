package org.openwebdav.messenger.directory

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.MessageCrypto
import org.openwebdav.messenger.crypto.NativeCrypto
import org.openwebdav.messenger.identity.IdentityCrypto
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C8: a native AEAD seal failure during [DirectoryService.publishEntry] must degrade to the typed
 * [PublishOutcome.Failed] — never propagate an uncaught exception out of the publish call. A
 * [SealFailingNative] delegates every libsodium call to the real backend EXCEPT `aeadEncrypt`, which
 * throws the `IllegalStateException` a spurious native encrypt failure surfaces (the same
 * `check()`-thrown exception `LazySodiumCrypto.aeadEncrypt` raises). New test file; the existing
 * directory suites stay untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DirectorySealFailureTest {
    private lateinit var server: MockWebServer
    private lateinit var disk: DirectoryFakeDisk

    @Before
    fun setUp() {
        server = MockWebServer()
        disk = DirectoryFakeDisk()
        server.dispatcher = disk
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun serviceWith(native: NativeCrypto): DirectoryService {
        val crypto = DirectoryCrypto.create(MessageCrypto(Aead(native)), IdentityCrypto(native))
        return DirectoryService(DirectoryTestSupport.transport(server), crypto)
    }

    // publish_degrades_on_native_seal_failure — a seal-failing native makes publishEntry return Failed,
    // not crash. No exception escapes the call (the test would fail with the uncaught throwable otherwise).
    @Test
    fun publish_degrades_on_native_seal_failure() =
        runTest {
            val sealFailing = SealFailingNative(DirectoryTestSupport.native())
            val service = serviceWith(sealFailing)
            val outcome = service.publishEntry(DirectoryTestSupport.newIdentity(), "Alice", 1, DirectoryTestSupport.communityKey())
            assertTrue("a native seal failure must map to the typed Failed", outcome is PublishOutcome.Failed)
            // Nothing was written to the disk (the seal failed before any PUT).
            assertTrue("no entry file written on a seal failure", disk.fileNames(DirectoryPaths.DIRECTORY).isEmpty())
        }

    // publish_fails_leaves_read_loop_usable — after an induced seal failure on publish, a subsequent
    // read/verify on the SAME service still succeeds (no corrupted crypto state, wipe-in-finally ran).
    @Test
    fun publish_fails_leaves_read_loop_usable() =
        runTest {
            val key = DirectoryTestSupport.communityKey()
            // 1) A real entry is on disk (sealed by a working backend).
            val good = DirectoryTestSupport.sealEntry(DirectoryTestSupport.newIdentity(), "Already-There", 1, key)
            disk.putFile(DirectoryPaths.entryPath(good.name), good.bytes)

            // 2) A publish that fails at the native seal — the service must not be wedged by it.
            val service = serviceWith(SealFailingNative(DirectoryTestSupport.native()))
            val failed = service.publishEntry(DirectoryTestSupport.newIdentity(), "Will-Fail", 1, key)
            assertTrue(failed is PublishOutcome.Failed)

            // 3) A read on the same service still verifies the pre-existing entry (the open path uses
            // aeadDecrypt, which the seal-failing native delegates to the real backend — read is intact).
            val read = service.readDirectory(key)
            assertEquals(listOf("Already-There"), read.entries.map { it.displayName })
        }
}
