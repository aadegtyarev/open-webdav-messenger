package org.openwebdav.messenger.chatdirectory

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
 * §11 chat-directory stack-spec tests: each asserts a cited rule from `docs/stack-notes.md` (Crypto +
 * OkHttp/WebDAV), `docs/architecture.md` (SCn), and `docs/protocol/webdav-layout.md`, verifying against
 * the rule (not the coder's own mapping). Each test references its source URL. Mirrors
 * `directory/DirectoryStackSpecTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ChatDirectoryStackSpecTest {
    private lateinit var server: MockWebServer
    private lateinit var disk: ChatDirectoryFakeDisk

    @Before
    fun setUp() {
        server = MockWebServer()
        disk = ChatDirectoryFakeDisk()
        server.dispatcher = disk
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // chat_entry_signature_hard_rejects_on_failure — a bad/absent signature → verify false → entry
    // dropped (libsodium crypto_sign_verify_detached returns -1 on failure; treated as a HARD reject,
    // never best-effort). Source: https://doc.libsodium.org/public-key_cryptography/public-key_signatures
    @Test
    fun chat_entry_signature_hard_rejects_on_failure() {
        val codec = ChatDirectoryTestSupport.codec()
        val identity = ChatDirectoryTestSupport.newIdentity()
        // A valid descriptor verifies.
        val valid =
            codec.signAndSerialize(
                chatId = "chat-x".toByteArray(),
                kind = ChatKind.GROUP,
                access = ChatAccess.PUBLIC,
                title = "Alpha",
                signingPublic = identity.copySignPublic(),
                versionCounter = 1,
                signingSecret = identity.copySignSecret(),
            )
        assertTrue(codec.parse(valid) is ChatParseResult.Parsed)
        // Flip one byte of the trailing signature → verify must HARD reject (not accept best-effort).
        val tampered = valid.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()
        val result = codec.parse(tampered)
        assertTrue(result is ChatParseResult.Rejected)
        assertEquals(ChatRejectReason.BAD_SIGNATURE, (result as ChatParseResult.Rejected).reason)
        // An all-zero signature is likewise rejected (verify never true-by-default).
        val sigBytes = ChatDescriptorFormat.SIGNATURE_BYTES
        val zeroSig = valid.copyOfRange(0, valid.size - sigBytes) + ByteArray(sigBytes)
        assertTrue(codec.parse(zeroSig) is ChatParseResult.Rejected)
    }

    // chat_entry_aead_uses_24_byte_random_nonce — the on-disk entry blob carries a 24-byte nonce (the
    // XChaCha20-Poly1305 192-bit nonce) freshly generated per seal, so two seals of the SAME descriptor
    // produce different on-disk bytes (the §5.1 framing the chat directory reuses). Source:
    // https://doc.libsodium.org/secret-key_cryptography/aead ; webdav-layout.md §5.1 / §11.2
    @Test
    fun chat_entry_aead_uses_24_byte_random_nonce() {
        assertEquals(24, AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES)
        val key = ChatDirectoryTestSupport.communityKey()
        val identity = ChatDirectoryTestSupport.newIdentity()
        val crypto = ChatDirectoryTestSupport.chatDirectoryCrypto()
        val chatId = "chat-x".toByteArray()
        val a =
            crypto.sealDescriptor(
                chatId,
                ChatKind.GROUP,
                ChatAccess.PUBLIC,
                "A",
                identity.copySignPublic(),
                1,
                identity.copySignSecret(),
                key,
            )
        val b =
            crypto.sealDescriptor(
                chatId,
                ChatKind.GROUP,
                ChatAccess.PUBLIC,
                "A",
                identity.copySignPublic(),
                1,
                identity.copySignSecret(),
                key,
            )
        // The §5 header (8 bytes) is identical; the next 24 bytes are the §5.1 nonce — they must differ
        // between two seals (fresh random nonce per seal), proving the nonce is not fixed/reused.
        val nonceA = a.copyOfRange(Envelope.HEADER_SIZE, Envelope.HEADER_SIZE + 24)
        val nonceB = b.copyOfRange(Envelope.HEADER_SIZE, Envelope.HEADER_SIZE + 24)
        assertFalse("a fresh 24-byte nonce per seal → distinct nonces", nonceA.contentEquals(nonceB))
        // Both still open to the same verified descriptor under the community key.
        assertTrue(crypto.openDescriptor(a, key) is ChatParseResult.Parsed)
        assertTrue(crypto.openDescriptor(b, key) is ChatParseResult.Parsed)
    }

    // community_key_is_not_the_disk_credential — the community key used to seal chat entries is a
    // symmetric AEAD key independent of the WebDAV app-password (SC3 discipline), and it is NEVER
    // written to the disk (SC4/SC19 family): the sealed entry bytes contain neither the app-password nor
    // the raw key bytes. Source: docs/architecture.md SC3/SC19 ; docs/stack-notes.md Crypto.
    @Test
    fun community_key_is_not_the_disk_credential() =
        runTest {
            val communityKey = ChatDirectoryTestSupport.communityKey()
            val identity = ChatDirectoryTestSupport.newIdentity()
            val published =
                ChatDirectoryTestSupport.service(server)
                    .publishChatEntry(identity, "chat-x".toByteArray(), ChatKind.GROUP, ChatAccess.PUBLIC, "A", 1, communityKey)
                    as ChatPublishOutcome.Published
            val onDisk = disk.bytesOf(ChatDirectoryPaths.entryPath(published.entryName))!!

            // The disk credential (app-password) is configured in ConnectionConfig, not derived into the
            // community key — independent values, and the app-password never crosses to disk.
            assertFalse("app-password must not be on disk", containsSub(onDisk, "app-password".toByteArray()))
            // The raw 32-byte community key is never written to disk (SC4/SC19) — only AEAD ciphertext is.
            assertFalse("raw community key must not be on disk", containsSub(onDisk, communityKey.export()))
        }

    // chat_directory_path_rejects_traversal — an entry name containing `/`, `..`, or an out-of-alphabet
    // character is rejected as a well-formed §11.4 name (never dereferenced — SC16). Source:
    // docs/architecture.md SC16 ; docs/protocol/webdav-layout.md §0 / §11.4.
    @Test
    fun chat_directory_path_rejects_traversal() {
        assertFalse(ChatDirectoryPaths.isWellFormedEntryName("../secret"))
        assertFalse(ChatDirectoryPaths.isWellFormedEntryName("a/b"))
        assertFalse(ChatDirectoryPaths.isWellFormedEntryName(".."))
        assertFalse(ChatDirectoryPaths.isWellFormedEntryName("UPPER".repeat(7)))
        assertFalse(ChatDirectoryPaths.isWellFormedEntryName("short"))
        assertFalse(ChatDirectoryPaths.isWellFormedEntryName("has space".padEnd(32, 'a')))
        // A real content-addressed name IS well-formed (32 chars, [a-z2-7]).
        val key = ChatDirectoryTestSupport.communityKey()
        val identity = ChatDirectoryTestSupport.newIdentity()
        val sealed = ChatDirectoryTestSupport.sealDescriptor(identity, "chat-x".toByteArray(), ChatAccess.PUBLIC, "A", 1, key)
        assertTrue(ChatDirectoryPaths.isWellFormedEntryName(sealed.name))
    }

    // chat_directory_propfind_depth_is_one — the chat-directory listing issues PROPFIND Depth: 1 (not
    // infinity). Servers MUST support Depth 0/1; infinity MAY be disabled. Source:
    // https://www.rfc-editor.org/rfc/rfc4918 (§9.1) ; docs/protocol/webdav-layout.md §6 / §11.6.
    @Test
    fun chat_directory_propfind_depth_is_one() =
        runTest {
            val key = ChatDirectoryTestSupport.communityKey()
            ChatDirectoryTestSupport.service(server)
                .publishChatEntry(
                    ChatDirectoryTestSupport.newIdentity(),
                    "chat-x".toByteArray(),
                    ChatKind.GROUP,
                    ChatAccess.PUBLIC,
                    "A",
                    1,
                    key,
                )
            disk.propfindDepths.clear()
            ChatDirectoryTestSupport.service(server).readChatDirectory(key)
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
