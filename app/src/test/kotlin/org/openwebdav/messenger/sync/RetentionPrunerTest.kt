package org.openwebdav.messenger.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.protocol.Base32
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.days

class RetentionPrunerTest {
    private val epoch = Instant.parse("2026-06-16T00:00:00Z")
    private val clock = Clock.fixed(epoch, ZoneOffset.UTC)
    private val window = 14.days

    @Test
    fun decodeTimestamp_extracts_11_char_prefix() {
        val now = epoch.toEpochMilli()
        val token = Base32.encodeBase32HexFixed(now, 11) + "-sender01-00000001"
        val decoded = RetentionPruner.decodeTimestamp(token)
        assertEquals(now, decoded)
    }

    @Test
    fun decodeTimestamp_returns_null_for_short_token() {
        assertNull(RetentionPruner.decodeTimestamp("short"))
        assertNull(RetentionPruner.decodeTimestamp(""))
    }

    @Test
    fun decodeTimestamp_returns_null_for_invalid_chars() {
        assertNull(RetentionPruner.decodeTimestamp("???????????"))
    }

    @Test
    fun isExpired_true_when_older_than_window() {
        val fourteenDaysAgo = epoch.minusSeconds(14 * 86400 + 1)
        assertTrue(RetentionPruner.isExpired(fourteenDaysAgo.toEpochMilli(), clock, window))
    }

    @Test
    fun isExpired_false_when_within_window() {
        val thirteenDaysAgo = epoch.minusSeconds(13 * 86400)
        assertFalse(RetentionPruner.isExpired(thirteenDaysAgo.toEpochMilli(), clock, window))
    }

    @Test
    fun isExpired_false_at_exact_boundary() {
        val exactlyWindowAgo = epoch.minusSeconds(14 * 86400)
        assertFalse(RetentionPruner.isExpired(exactlyWindowAgo.toEpochMilli(), clock, window))
    }

    @Test
    fun isExpired_true_for_epoch_zero() {
        assertTrue(RetentionPruner.isExpired(0L, clock, window))
    }
}
