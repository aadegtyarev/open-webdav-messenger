package org.openwebdav.messenger.governance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openwebdav.messenger.chatdirectory.ChatAccess

class CommunityCreationPolicyRulesTest {
    @Test
    fun creation_matrix_preserves_legacy_default_and_independent_types() {
        assertTrue(CommunityCreationPolicyRules.allowsCreation(CommunityCreationPolicyService.Result.DefaultAllowed, ChatAccess.PUBLIC))
        assertTrue(CommunityCreationPolicyRules.allowsCreation(CommunityCreationPolicyService.Result.DefaultAllowed, ChatAccess.PRIVATE))
        val policy = policy(allowPublic = true, allowPrivate = false)
        assertTrue(CommunityCreationPolicyRules.allowsCreation(policy, ChatAccess.PUBLIC))
        assertFalse(CommunityCreationPolicyRules.allowsCreation(policy, ChatAccess.PRIVATE))
        assertFalse(CommunityCreationPolicyRules.allowsCreation(CommunityCreationPolicyService.Result.Blocked, ChatAccess.PUBLIC))
    }

    @Test
    fun public_discovery_accepts_only_policy_allowed_or_exact_grandfathered_origin() {
        val denied = policy(allowPublic = false, allowPrivate = true, grandfathered = listOf(HASH))
        assertTrue(CommunityCreationPolicyRules.allowsDiscoveredPublic(denied, HASH))
        assertFalse(CommunityCreationPolicyRules.allowsDiscoveredPublic(denied, "b".repeat(32)))
        assertFalse(CommunityCreationPolicyRules.allowsDiscoveredPublic(denied, null))
        assertFalse(CommunityCreationPolicyRules.allowsDiscoveredPublic(CommunityCreationPolicyService.Result.Blocked, HASH))
    }

    private fun policy(
        allowPublic: Boolean,
        allowPrivate: Boolean,
        grandfathered: List<String> = emptyList(),
    ) = CommunityCreationPolicyService.Result.Policy(
        CommunityCreationPolicy("community-1", 1, allowPublic, allowPrivate, grandfathered, ByteArray(64)),
    )

    private companion object {
        val HASH = "a".repeat(32)
    }
}
