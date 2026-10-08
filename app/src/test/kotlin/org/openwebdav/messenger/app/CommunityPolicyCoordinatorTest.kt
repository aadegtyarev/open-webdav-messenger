package org.openwebdav.messenger.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityPolicyCoordinatorTest {
    @Test
    fun rapid_edits_across_controls_keep_the_combined_pending_policy() {
        val coordinator = CommunityPolicyCoordinator()
        val committed = CommunityPolicy(retentionDays = 14, pollFloorSeconds = 60)
        val retention = coordinator.retention("community-a", committed, 30)
        val pollFloor = coordinator.pollFloor("community-a", committed, 300)

        assertEquals(CommunityPolicy(30, 300), pollFloor.policy)
        assertFalse(coordinator.isLatest(retention))
        assertTrue(coordinator.isLatest(pollFloor))
    }
}
