package org.openwebdav.messenger.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityPolicyCoordinatorTest {
    @Test
    fun completing_pre_reset_request_does_not_clear_same_policy_after_reset() {
        val coordinator = CommunityPolicyCoordinator()
        val committed = CommunityPolicy(retentionDays = 14, pollFloorSeconds = 60)
        val requestA = coordinator.retention("community-a", committed, 30)
        coordinator.reset()
        val requestB = coordinator.retention("community-a", committed, 30)

        assertEquals(requestA.policy, requestB.policy)
        assertNotEquals(requestA.revision, requestB.revision)
        coordinator.complete(requestA)
        assertTrue(coordinator.isLatest(requestB))
        coordinator.complete(requestB)
        assertFalse(coordinator.isLatest(requestB))
    }

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
