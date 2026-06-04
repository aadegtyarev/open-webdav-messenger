package org.openwebdav.messenger.directory

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §10.6 directory rejection tests (`docs/features/directory_plan.md` Test plan): every failure mode is a
 * typed rejection — the entry is dropped, never surfaced, never a crash — and the remaining valid
 * entries still read. Covers wrong/absent community key, tampered ciphertext/payload, wrong signature,
 * malformed/truncated/wrong-magic, foreign name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DirectoryRejectionTest {
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

    /** read_without_community_key_yields_nothing_readable: a wrong community key drops the entry, no crash. */
    @Test
    fun read_without_community_key_yields_nothing_readable() =
        runTest {
            val realKey = DirectoryTestSupport.communityKey(seed = 1)
            val wrongKey = DirectoryTestSupport.communityKey(seed = 2)
            val identity = DirectoryTestSupport.newIdentity()
            DirectoryTestSupport.service(server).publishEntry(identity, "Alice", 1, realKey)

            val read = DirectoryTestSupport.service(server).readDirectory(wrongKey)
            assertTrue("no entry readable with the wrong key", read.entries.isEmpty())
            assertEquals(1, read.rejectedCount)
        }

    /**
     * tampered_entry_rejected: an entry whose ciphertext is flipped by one byte is dropped (AEAD reject),
     * and another valid entry still reads.
     */
    @Test
    fun tampered_entry_rejected_other_entries_still_read() =
        runTest {
            val key = DirectoryTestSupport.communityKey()
            val service = DirectoryTestSupport.service(server)
            // A valid entry that must survive.
            service.publishEntry(DirectoryTestSupport.newIdentity(), "Valid", 1, key)
            // A tampered entry: flip a ciphertext byte, then store it under the RECOMPUTED content name
            // so the §3/§10.6 on-read hash check passes and the AEAD Poly1305 tag is the line of defence
            // that rejects it (the entry is dropped without wedging the read).
            val sealed = DirectoryTestSupport.sealEntry(DirectoryTestSupport.newIdentity(), "Tampered", 1, key)
            val flipped = sealed.bytes.copyOf()
            flipped[flipped.size - 1] = (flipped[flipped.size - 1].toInt() xor 0x01).toByte()
            val flippedName = DirectoryPaths.entryName(flipped)
            disk.putFile(DirectoryPaths.entryPath(flippedName), flipped)

            val read = service.readDirectory(key)
            assertEquals(listOf("Valid"), read.entries.map { it.displayName })
            assertEquals(1, read.rejectedCount)
        }

    /**
     * wrong_signature_rejected: an entry signed by a DIFFERENT key than the one it carries is dropped
     * (hard reject on libsodium -1). Built by sealing a payload whose signing-pubkey field is one
     * identity's but whose signature is another's — the codec verifies against the carried key.
     */
    @Test
    fun wrong_signature_rejected() =
        runTest {
            val key = DirectoryTestSupport.communityKey()
            val identity = DirectoryTestSupport.newIdentity()
            val impostor = DirectoryTestSupport.newIdentity()
            // Serialize an inner payload that CLAIMS `identity`'s signing key but is signed by `impostor`.
            val codec = DirectoryEntryCodec(DirectoryTestSupport.identityCrypto())
            // signingPublic = `identity`'s CLAIMED signing key, but signingSecret = `impostor`'s — the
            // codec verifies the signature against the carried (claimed) key, so it must reject.
            val forged =
                codec.signAndSerialize(
                    displayName = "Impostor",
                    signingPublic = identity.copySignPublic(),
                    boxPublic = identity.copyBoxPublic(),
                    versionCounter = 1,
                    signingSecret = impostor.copySignSecret(),
                )
            // The codec parse must reject it (verify against the carried key fails) — assert directly.
            assertTrue(codec.parse(forged) is DirectoryParseResult.Rejected)
            assertEquals(
                DirectoryRejectReason.BAD_SIGNATURE,
                (codec.parse(forged) as DirectoryParseResult.Rejected).reason,
            )
        }

    /**
     * malformed_or_truncated_entry_rejected: garbage / truncated / wrong-magic entry blobs are typed
     * rejections, not bounds errors or crashes — at both the codec layer and through the full read.
     */
    @Test
    fun malformed_or_truncated_entry_rejected() =
        runTest {
            val codec = DirectoryEntryCodec(DirectoryTestSupport.identityCrypto())
            // Empty / too-short payloads → MALFORMED, never a crash.
            assertTrue(codec.parse(ByteArray(0)) is DirectoryParseResult.Rejected)
            assertTrue(codec.parse(ByteArray(10)) is DirectoryParseResult.Rejected)
            assertTrue(codec.parse(ByteArray(DirectoryFormat.MIN_PAYLOAD_BYTES - 1)) is DirectoryParseResult.Rejected)

            // An unknown dir-entry-version is a reject-don't-guess (§10.3) — build a min-size buffer
            // whose first byte is a bogus version.
            val bogusVersion = ByteArray(DirectoryFormat.MIN_PAYLOAD_BYTES) { 0 }
            bogusVersion[0] = 0x7F
            assertEquals(
                DirectoryRejectReason.UNKNOWN_VERSION,
                (codec.parse(bogusVersion) as DirectoryParseResult.Rejected).reason,
            )

            // A wrong-magic FILE blob fed through the full read path → dropped (the §5 frame parse / §3
            // hash check fails), no crash, empty result.
            val key = DirectoryTestSupport.communityKey()
            disk.putFile(DirectoryPaths.entryPath("a".repeat(DirectoryPaths.ENTRY_NAME_LEN)), "not an envelope".toByteArray())
            val read = DirectoryTestSupport.service(server).readDirectory(key)
            assertTrue(read.entries.isEmpty())
            assertEquals(1, read.rejectedCount)
        }

    /**
     * A display-name longer than the §10.3 cap arriving FROM disk is a typed reject (not a throw): the
     * codec rejects an over-cap length prefix. (The publish path guards the cap with require, so this
     * exercises the read-side defence against a hand-crafted over-cap entry.)
     */
    @Test
    fun over_cap_display_name_rejected_on_read() {
        val codec = DirectoryEntryCodec(DirectoryTestSupport.identityCrypto())
        val identity = DirectoryTestSupport.newIdentity()
        // Hand-build an inner payload with a display-name-len above the cap but consistent with bytes.
        val tooLong = DirectoryFormat.MAX_DISPLAY_NAME_BYTES + 1
        val name = ByteArray(tooLong) { 'x'.code.toByte() }
        val signed =
            buildInner(
                version = DirectoryFormat.ENTRY_VERSION,
                signingPub = identity.copySignPublic(),
                boxPub = identity.copyBoxPublic(),
                versionCounter = 1,
                nameBytes = name,
            )
        val sig = DirectoryTestSupport.identityCrypto().sign(signed, identity.copySignSecret())
        val payload = signed + sig
        assertEquals(
            DirectoryRejectReason.MALFORMED,
            (codec.parse(payload) as DirectoryParseResult.Rejected).reason,
        )
    }

    private fun buildInner(
        version: Byte,
        signingPub: ByteArray,
        boxPub: ByteArray,
        versionCounter: Long,
        nameBytes: ByteArray,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(version.toInt())
        out.write(signingPub)
        out.write(boxPub)
        for (i in 7 downTo 0) out.write(((versionCounter ushr (8 * i)) and 0xFF).toInt())
        out.write((nameBytes.size ushr 8) and 0xFF)
        out.write(nameBytes.size and 0xFF)
        out.write(nameBytes)
        return out.toByteArray()
    }
}
