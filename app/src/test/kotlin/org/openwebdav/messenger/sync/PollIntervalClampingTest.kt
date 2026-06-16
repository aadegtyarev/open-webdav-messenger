package org.openwebdav.messenger.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class PollIntervalClampingTest {
    @Test
    fun syncScheduler_member_above_floor_uses_member_pref() {
        assertEquals(30L, SyncScheduler.effectiveIntervalMinutes(30L, 15))
    }

    @Test
    fun syncScheduler_member_below_floor_uses_floor() {
        assertEquals(15L, SyncScheduler.effectiveIntervalMinutes(5L, 15))
    }

    @Test
    fun syncScheduler_member_below_platform_floor_uses_platform() {
        assertEquals(15L, SyncScheduler.effectiveIntervalMinutes(1L, 1))
    }

    @Test
    fun syncScheduler_null_community_floor_defaults_to_platform() {
        assertEquals(15L, SyncScheduler.effectiveIntervalMinutes(5L, null))
    }

    @Test
    fun syncScheduler_all_equal_returns_value() {
        assertEquals(15L, SyncScheduler.effectiveIntervalMinutes(15L, 15))
    }

    @Test
    fun fastPoll_platform_floor_is_1_minute() {
        assertEquals(1L, FastPollManager.PLATFORM_FLOOR_MINUTES)
    }
}
