package org.openwebdav.messenger.membership

internal sealed interface MembershipPublishOutcome {
    data object Published : MembershipPublishOutcome

    data class Failed(val reason: String) : MembershipPublishOutcome
}

internal data class PrivateMembershipReadResult(
    val members: List<VerifiedPrivateMember>,
    val rejectedCount: Int,
    val listingFailed: Boolean,
)
