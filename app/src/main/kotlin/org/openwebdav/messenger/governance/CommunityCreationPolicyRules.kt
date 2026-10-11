package org.openwebdav.messenger.governance

import org.openwebdav.messenger.chatdirectory.ChatAccess

internal object CommunityCreationPolicyRules {
    fun allowsCreation(
        result: CommunityCreationPolicyService.Result,
        access: ChatAccess,
    ): Boolean =
        when (result) {
            CommunityCreationPolicyService.Result.DefaultAllowed -> true
            CommunityCreationPolicyService.Result.Blocked -> false
            is CommunityCreationPolicyService.Result.Policy ->
                if (access == ChatAccess.PUBLIC) result.value.allowPublic else result.value.allowPrivate
        }

    fun allowsDiscoveredPublic(
        result: CommunityCreationPolicyService.Result,
        immutableOriginHash: String?,
    ): Boolean =
        when (result) {
            CommunityCreationPolicyService.Result.DefaultAllowed -> true
            CommunityCreationPolicyService.Result.Blocked -> false
            is CommunityCreationPolicyService.Result.Policy ->
                result.value.allowPublic || immutableOriginHash in result.value.grandfatheredOriginHashes
        }
}
