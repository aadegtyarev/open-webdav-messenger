package org.openwebdav.messenger.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class PollIntervalClampingTest {
    @Test
    fun syncScheduler_member_above_floor_uses_member_pref() {
        assertEquals(120L, SyncScheduler.effectiveIntervalSeconds(120L, 60))
    }

    @Test
    fun syncScheduler_member_below_floor_uses_floor() {
        assertEquals(60L, SyncScheduler.effectiveIntervalSeconds(30L, 60))
    }

    @Test
    fun syncScheduler_member_below_platform_floor_uses_platform() {
        assertEquals(60L, SyncScheduler.effectiveIntervalSeconds(15L, 15))
    }

    @Test
    fun syncScheduler_null_community_floor_defaults_to_platform() {
        assertEquals(60L, SyncScheduler.effectiveIntervalSeconds(30L, null))
    }

    @Test
    fun syncScheduler_all_equal_returns_value() {
        assertEquals(60L, SyncScheduler.effectiveIntervalSeconds(60L, 60))
    }

    @Test
    fun fastPoll_platform_floor_is_15_seconds() {
        assertEquals(15L, FastPollManager.PLATFORM_FLOOR_SECONDS)
    }
}
