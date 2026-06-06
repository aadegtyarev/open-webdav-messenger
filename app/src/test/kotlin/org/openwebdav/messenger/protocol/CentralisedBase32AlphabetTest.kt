package org.openwebdav.messenger.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.chatdirectory.ChatDirectoryPaths
import org.openwebdav.messenger.directory.DirectoryPaths

/**
 * A3 centralised Base32-lower alphabet: the single [HashTag.BASE32_LOWER_CHARS] constant must equal the
 * RFC-4648 Base32 set lowercased (`a–z 2–7`, no padding), and the four former call sites (`MessageId`,
 * `ChangeEntry`, `DirectoryPaths`, `ChatDirectoryPaths`) must accept/reject exactly the same characters
 * through it. New test file; the existing path/name suites stay untouched.
 *
 * Source: RFC 4648 §6 "Base 32 Encoding" — the 32-symbol alphabet is `A–Z` then `2 3 4 5 6 7`
 * (<https://www.rfc-editor.org/rfc/rfc4648#section-6>); this project uses the lowercased form.
 */
class CentralisedBase32AlphabetTest {
    private val rfc4648Base32Lower: Set<Char> = (('a'..'z') + ('2'..'7')).toSet()

    @Test
    fun constant_equals_rfc4648_base32_lower_set() {
        assertEquals(32, rfc4648Base32Lower.size)
        assertEquals(rfc4648Base32Lower, HashTag.BASE32_LOWER_CHARS)
        // The digits 0/1/8/9 are deliberately EXCLUDED (RFC 4648 omits them to avoid 0/O, 1/l ambiguity);
        // uppercase is excluded by the lowercase choice.
        for (c in listOf('0', '1', '8', '9', 'A', 'Z', '=', '/', '~')) {
            assertFalse("$c must not be in the base32-lower set", c in HashTag.BASE32_LOWER_CHARS)
        }
        for (c in ('a'..'z') + ('2'..'7')) {
            assertTrue("$c must be in the base32-lower set", c in HashTag.BASE32_LOWER_CHARS)
        }
    }

    // A 32-char name with an excluded character (0/1/8/9 or uppercase) is rejected by every former call
    // site's well-formed-name gate; a name over a–z2–7 is accepted. (The exact lengths differ per site —
    // 32 for the two directory paths, 16 chat-tag + 29 order-token for MessageId/ChangeEntry — so each
    // gate is fed a name of its own width.)
    @Test
    fun all_four_call_sites_share_the_same_charset_gate() {
        // §10.4 / §11.4 directory entry names: exactly 32 chars over the alphabet.
        val goodName = "abcdefghijklmnopqrstuvwxyz234567" // 32 chars, all in-alphabet
        assertEquals(32, goodName.length)
        assertTrue(DirectoryPaths.isWellFormedEntryName(goodName))
        assertTrue(ChatDirectoryPaths.isWellFormedEntryName(goodName))
        for (bad in listOf('0', '1', '8', '9', 'A')) {
            val name = bad + goodName.substring(1) // still 32 chars, one out-of-alphabet char
            assertFalse("directory gate must reject '$bad'", DirectoryPaths.isWellFormedEntryName(name))
            assertFalse("chat-directory gate must reject '$bad'", ChatDirectoryPaths.isWellFormedEntryName(name))
        }

        // §2 message-id content-hash (32 chars over the alphabet) + §9.2 chat-tag (16 chars). A
        // content-hash with an out-of-alphabet char fails MessageId's well-formed gate; a chat-tag with
        // one fails ChangeEntry's parse — same alphabet, same exclusions.
        // A real §4 order-token (29 chars over [0-9a-z-]) — built so the fixture cannot drift from the format.
        val orderToken = OrderToken.build(unixMillis = 1_717_000_000_000L, senderIdentifier = "sender", seq = 1L)
        assertEquals(OrderToken.LENGTH, orderToken.length)
        assertTrue(MessageId.isWellFormedMessageId("$orderToken~$goodName"))
        assertFalse(MessageId.isWellFormedMessageId("$orderToken~" + "0" + goodName.substring(1)))

        val goodChatTag = "abcdefghijklmnop" // 16 chars over the alphabet
        assertEquals(16, goodChatTag.length)
        assertTrue(ChangeEntry.parse("$goodChatTag~$orderToken") != null)
        assertTrue(ChangeEntry.parse(("0" + goodChatTag.substring(1)) + "~" + orderToken) == null)
    }
}
