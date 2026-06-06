package org.openwebdav.messenger.message

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A1 shared-cursor parity: the one bounded [ByteCursor] that now backs the §8 message reader, the §10.3
 * `DirectoryEntryCodec`, and the §11.3 `ChatDescriptorCodec` must accept/reject truncated, exact-boundary,
 * oversized, and well-formed inputs IDENTICALLY to the behaviour the three former copies enforced —
 * reject-don't-guess on untrusted bytes (SC16), null-on-overrun (no `!!` on parse paths). New test file;
 * the existing codec/message suites already pin the higher-level parse behaviour and stay untouched.
 */
class SharedByteCursorTest {
    // Big-endian read values match the writer side (BigEndian) — a u16/u64 reads MSB-first.
    @Test
    fun reads_big_endian_values_at_exact_widths() {
        // u8(0xAB) ‖ u16(0x0102) ‖ u64(1) ‖ u8(0x7F) = 1 + 2 + 8 + 1 = 12 bytes.
        val buf = byteArrayOf(0xAB.toByte(), 0x01, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x7F)
        val cursor = ByteCursor(buf)
        assertEquals(0xAB, cursor.u8())
        assertEquals(0x0102, cursor.u16())
        assertEquals(1L, cursor.u64())
        assertEquals(0x7F, cursor.u8())
        // The buffer is now exhausted — any further read is null, never an exception.
        assertNull(cursor.u8())
    }

    @Test
    fun take_returns_exact_bytes_then_advances() {
        val cursor = ByteCursor(byteArrayOf(1, 2, 3, 4))
        assertArrayEquals(byteArrayOf(1, 2), cursor.take(2))
        assertEquals(2, cursor.pos)
        assertArrayEquals(byteArrayOf(3, 4), cursor.take(2))
        // Reading zero bytes at the end is a valid empty array, not an overrun.
        assertArrayEquals(ByteArray(0), cursor.take(0))
    }

    // Overrun (every form) → null, position unmoved, no IndexOutOfBounds escaping. This is the
    // reject-don't-guess guarantee every parse path depends on (§8.1 / §10.3 / §11.3).
    @Test
    fun overrun_returns_null_for_every_read_form() {
        assertNull(ByteCursor(byteArrayOf(1)).take(2)) // ask 2, only 1 remains
        assertNull(ByteCursor(ByteArray(0)).u8()) // empty
        assertNull(ByteCursor(byteArrayOf(1)).u16()) // 1 < 2
        assertNull(ByteCursor(byteArrayOf(1, 2, 3, 4, 5, 6, 7)).u64()) // 7 < 8
        // A negative take is a reject (the §8.1 negative-length guard), never an exception.
        assertNull(ByteCursor(byteArrayOf(1, 2, 3)).take(-1))
    }

    // The limit ceiling: with limit < buf.size, reads stop at limit (the start of the trailing signature
    // in the codecs) and never cross into the bytes beyond it — exactly the former bounded Cursor copies.
    @Test
    fun limit_ceiling_bounds_reads_below_buffer_end() {
        val buf = byteArrayOf(1, 2, 3, 4, 5, 6)
        // limit = 3: only the first three bytes are readable even though the buffer has six.
        val cursor = ByteCursor(buf, limit = 3)
        assertArrayEquals(byteArrayOf(1, 2), cursor.take(2))
        assertNull("a take crossing the limit is rejected", cursor.take(2)) // 1 left before limit, ask 2
        assertEquals(3, cursor.u8()) // reads the third byte (value 3) — the last byte before the limit
        assertNull("nothing remains before the limit", cursor.u8())
    }

    // u64 exact-boundary: exactly 8 bytes before the limit reads; one short rejects.
    @Test
    fun u64_boundary_reads_at_exactly_eight_else_rejects() {
        val eight = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0x05)
        assertEquals(5L, ByteCursor(eight).u64())
        // limit one short of 8 → reject.
        assertNull(ByteCursor(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 5), limit = 7).u64())
        // A full uint64 (all bytes 0xFF) decodes to -1L (Long) — the unsigned-aware value the supersede
        // resolver compares; proves no sign-truncation in the shared reader.
        assertEquals(-1L, ByteCursor(ByteArray(8) { 0xFF.toByte() }).u64())
    }
}
