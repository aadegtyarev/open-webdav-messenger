package org.openwebdav.messenger.directory

import com.goterl.lazysodium.interfaces.AEAD
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openwebdav.messenger.protocol.Envelope
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §10 directory stack-spec tests: each asserts a cited rule from `docs/stack-notes.md` (Crypto +
 * OkHttp/WebDAV) and `docs/protocol/webdav-layout.md`, verifying against the rule (not the coder's own
 * mapping). Each test references its source URL. Mirrors `crypto`/`identity`/`message`/`sync` stack-spec
 * suites.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DirectoryStackSpecTest {
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

    // entry_signature_hard_rejects_on_failure — a bad/absent signature → verify false → entry dropped
    // (libsodium crypto_sign_verify_detached returns -1 on failure; treated as a HARD reject, never
    // best-effort). Source: https://doc.libsodium.org/public-key_cryptography/public-key_signatures
    @Test
    fun entry_signature_hard_rejects_on_failure() {
        val codec = DirectoryEntryCodec(DirectoryTestSupport.identityCrypto())
        val identity = DirectoryTestSupport.newIdentity()
        // A valid entry verifies.
        val valid =
            codec.signAndSerialize("Alice", identity.copySignPublic(), identity.copyBoxPublic(), 1, identity.copySignSecret())
        assertTrue(codec.parse(valid) is DirectoryParseResult.Parsed)
        // Flip one byte of the trailing signature → verify must HARD reject (not accept best-effort).
        val tampered = valid.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()
        val result = codec.parse(tampered)
        assertTrue(result is DirectoryParseResult.Rejected)
        assertEquals(DirectoryRejectReason.BAD_SIGNATURE, (result as DirectoryParseResult.Rejected).reason)
        // An all-zero signature is likewise rejected (verify never true-by-default).
        val zeroSig = valid.copyOfRange(0, valid.size - DirectoryFormat.SIGNATURE_BYTES) + ByteArray(DirectoryFormat.SIGNATURE_BYTES)
        assertTrue(codec.parse(zeroSig) is DirectoryParseResult.Rejected)
    }

    // entry_aead_uses_24_byte_random_nonce — the on-disk entry blob carries a 24-byte nonce (the
    // XChaCha20-Poly1305 192-bit nonce) freshly generated per seal, so two seals of the SAME entry
    // produce different on-disk bytes (the §5.1 framing the directory reuses). Source:
    // https://doc.libsodium.org/secret-key_cryptography/aead ; webdav-layout.md §5.1 / §10.2
    @Test
    fun entry_aead_uses_24_byte_random_nonce() {
        assertEquals(24, AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES)
        val key = DirectoryTestSupport.communityKey()
        val identity = DirectoryTestSupport.newIdentity()
        val crypto = DirectoryTestSupport.directoryCrypto()
        val a = crypto.sealEntry("Alice", identity.copySignPublic(), identity.copyBoxPublic(), 1, identity.copySignSecret(), key)
        val b = crypto.sealEntry("Alice", identity.copySignPublic(), identity.copyBoxPublic(), 1, identity.copySignSecret(), key)
        // The §5 header (8 bytes) is identical; the next 24 bytes are the §5.1 nonce — they must differ
        // between two seals (fresh random nonce per seal), proving the nonce is not fixed/reused.
        val nonceA = a.copyOfRange(Envelope.HEADER_SIZE, Envelope.HEADER_SIZE + 24)
        val nonceB = b.copyOfRange(Envelope.HEADER_SIZE, Envelope.HEADER_SIZE + 24)
        assertFalse("a fresh 24-byte nonce per seal → distinct nonces", nonceA.contentEquals(nonceB))
        // Both still open to the same verified entry under the community key.
        assertTrue(crypto.openEntry(a, key) is DirectoryParseResult.Parsed)
        assertTrue(crypto.openEntry(b, key) is DirectoryParseResult.Parsed)
    }

    // community_key_is_not_the_disk_credential — the community key used to seal entries is a symmetric
    // AEAD key independent of the WebDAV app-password (SC3 discipline), and it is NEVER written to the
    // disk (SC4 family): the sealed entry bytes contain neither the app-password nor the raw key bytes.
    // Source: docs/architecture.md SC3 ; docs/stack-notes.md Crypto.
    @Test
    fun community_key_is_not_the_disk_credential() =
        runTest {
            val communityKey = DirectoryTestSupport.communityKey()
            val identity = DirectoryTestSupport.newIdentity()
            val published =
                DirectoryTestSupport.service(
                    server,
                ).publishEntry(identity, "Alice", 1, communityKey) as PublishOutcome.Published
            val onDisk = disk.bytesOf(DirectoryPaths.entryPath(published.entryName))!!

            // The disk credential (app-password) is configured in ConnectionConfig, not derived into the
            // community key — they are independent values, and the app-password never crosses to disk.
            assertFalse("app-password must not be on disk", containsSub(onDisk, "app-password".toByteArray()))
            // The raw 32-byte community key is never written to disk (SC4) — only AEAD ciphertext is.
            assertFalse("raw community key must not be on disk", containsSub(onDisk, communityKey.export()))
        }

    // directory_path_rejects_traversal — an entry name containing `/`, `..`, or an out-of-alphabet
    // character is rejected as a well-formed §10.4 name (never dereferenced — SC16). The transport's
    // PathSafety additionally fail-closes a traversal path before any request. Source: docs/architecture.md
    // SC16 ; docs/protocol/webdav-layout.md §0 / §10.4.
    @Test
    fun directory_path_rejects_traversal() {
        // The §10.4 grammar is exactly 32 chars over [a-z2-7]; anything else is not well-formed and is
        // dropped before a GET (so a planted name like `../secret` is never dereferenced).
        assertFalse(DirectoryPaths.isWellFormedEntryName("../secret"))
        assertFalse(DirectoryPaths.isWellFormedEntryName("a/b"))
        assertFalse(DirectoryPaths.isWellFormedEntryName(".."))
        assertFalse(DirectoryPaths.isWellFormedEntryName("UPPER".repeat(7)))
        assertFalse(DirectoryPaths.isWellFormedEntryName("short"))
        assertFalse(DirectoryPaths.isWellFormedEntryName("has space".padEnd(32, 'a')))
        // A real content-addressed name IS well-formed (32 chars, [a-z2-7]).
        val key = DirectoryTestSupport.communityKey()
        val identity = DirectoryTestSupport.newIdentity()
        val sealed = DirectoryTestSupport.sealEntry(identity, "Alice", 1, key)
        assertTrue(DirectoryPaths.isWellFormedEntryName(sealed.name))
    }

    // propfind_depth_is_one — the directory listing issues PROPFIND Depth: 1 (not infinity). Servers
    // MUST support Depth 0/1; infinity MAY be disabled. Source: https://www.rfc-editor.org/rfc/rfc4918
    // (§9.1) ; docs/protocol/webdav-layout.md §6 / §10.6.
    @Test
    fun propfind_depth_is_one() =
        runTest {
            val key = DirectoryTestSupport.communityKey()
            DirectoryTestSupport.service(server).publishEntry(DirectoryTestSupport.newIdentity(), "Alice", 1, key)
            disk.propfindDepths.clear()
            DirectoryTestSupport.service(server).readDirectory(key)
            // Every PROPFIND the read issued used Depth: 1 — never infinity, never 0.
            assertTrue("read must issue at least one PROPFIND", disk.propfindDepths.isNotEmpty())
            assertTrue("all PROPFINDs use Depth: 1", disk.propfindDepths.all { it == "1" })
            assertNull(disk.propfindDepths.firstOrNull { it == "infinity" })
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
