package org.openwebdav.messenger.directory

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §10 directory publish/read round-trip + supersede + multi-member tests
 * (`docs/features/directory_plan.md` Test plan). Real libsodium-backed crypto + a MockWebServer-backed
 * transport over [DirectoryFakeDisk] — the full publish → read → verify path off-device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DirectoryRoundTripTest {
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

    /** publish_then_read_roundtrips_verified_entry: the verified entry carries the same name + both keys. */
    @Test
    fun publish_then_read_roundtrips_verified_entry() =
        runTest {
            val key = DirectoryTestSupport.communityKey()
            val identity = DirectoryTestSupport.newIdentity()
            val service = DirectoryTestSupport.service(server)

            val outcome = service.publishEntry(identity, "Alice", versionCounter = 1, communityKey = key)
            assertTrue(outcome is PublishOutcome.Published)

            val read = service.readDirectory(key)
            assertEquals(1, read.entries.size)
            val entry = read.entries.single()
            assertEquals("Alice", entry.displayName)
            assertArrayEquals(identity.copySignPublic(), entry.copySigningPublicKey())
            assertArrayEquals(identity.copyBoxPublic(), entry.copyBoxPublicKey())
            assertEquals(0, read.rejectedCount)
        }

    /**
     * entry_is_ciphertext_only_on_disk: the bytes on the disk contain neither the display-name nor the
     * public keys in cleartext (only AEAD ciphertext + the §5 public framing).
     */
    @Test
    fun entry_is_ciphertext_only_on_disk() =
        runTest {
            val key = DirectoryTestSupport.communityKey()
            val identity = DirectoryTestSupport.newIdentity()
            val service = DirectoryTestSupport.service(server)

            val outcome = service.publishEntry(identity, "SecretName", versionCounter = 1, communityKey = key) as PublishOutcome.Published
            val onDisk = disk.bytesOf(DirectoryPaths.entryPath(outcome.entryName))!!

            // The display-name bytes never appear in the on-disk blob (sealed under the community key).
            assertFalse("display-name must not be on disk in cleartext", containsSub(onDisk, "SecretName".toByteArray()))
            // Neither public key appears in cleartext (they are inside the AEAD-sealed payload, §10.3).
            assertFalse("signing pubkey must not be on disk in cleartext", containsSub(onDisk, identity.copySignPublic()))
            assertFalse("box pubkey must not be on disk in cleartext", containsSub(onDisk, identity.copyBoxPublic()))
        }

    /** updated_entry_supersedes_older: two valid entries for the same member → only the newer is returned. */
    @Test
    fun updated_entry_supersedes_older() =
        runTest {
            val key = DirectoryTestSupport.communityKey()
            val identity = DirectoryTestSupport.newIdentity()
            // Place an older (v1, "Old") and a newer (v2, "New") entry for the SAME signing key on disk.
            val older = DirectoryTestSupport.sealEntry(identity, "Old", versionCounter = 1, communityKey = key)
            val newer = DirectoryTestSupport.sealEntry(identity, "New", versionCounter = 2, communityKey = key)
            disk.putFile(DirectoryPaths.entryPath(older.name), older.bytes)
            disk.putFile(DirectoryPaths.entryPath(newer.name), newer.bytes)

            val read = DirectoryTestSupport.service(server).readDirectory(key)
            assertEquals("exactly one entry per member", 1, read.entries.size)
            assertEquals("New", read.entries.single().displayName)
        }

    /** multiple_members_listed: entries from several members all read back as distinct verified entries. */
    @Test
    fun multiple_members_listed() =
        runTest {
            val key = DirectoryTestSupport.communityKey()
            val service = DirectoryTestSupport.service(server)
            val alice = DirectoryTestSupport.newIdentity()
            val bob = DirectoryTestSupport.newIdentity()
            val carol = DirectoryTestSupport.newIdentity()
            service.publishEntry(alice, "Alice", 1, key)
            service.publishEntry(bob, "Bob", 1, key)
            service.publishEntry(carol, "Carol", 1, key)

            val read = service.readDirectory(key)
            assertEquals(3, read.entries.size)
            assertEquals(setOf("Alice", "Bob", "Carol"), read.entries.map { it.displayName }.toSet())
            // All three signing keys are distinct.
            assertEquals(3, read.entries.map { it.copySigningPublicKey().toList() }.toSet().size)
        }

    /** Idempotent re-publish of identical bytes lands at the same content-addressed name (§10.4). */
    @Test
    fun identical_republish_is_idempotent_same_name() =
        runTest {
            val key = DirectoryTestSupport.communityKey()
            val identity = DirectoryTestSupport.newIdentity()
            // Seal ONCE, then publish the SAME bytes twice (the §2/§10.4 idempotency precondition: reuse
            // the same bytes — a fresh seal would use a fresh nonce and differ).
            val sealed = DirectoryTestSupport.sealEntry(identity, "Alice", versionCounter = 1, communityKey = key)
            disk.putFile(DirectoryPaths.entryPath(sealed.name), sealed.bytes)
            disk.putFile(DirectoryPaths.entryPath(sealed.name), sealed.bytes)

            val read = DirectoryTestSupport.service(server).readDirectory(key)
            assertEquals(1, read.entries.size)
            assertEquals(DirectoryPaths.ENTRY_NAME_LEN, sealed.name.length)
        }

    private fun containsSub(
        haystack: ByteArray,
        needle: ByteArray,
    ): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }
}
