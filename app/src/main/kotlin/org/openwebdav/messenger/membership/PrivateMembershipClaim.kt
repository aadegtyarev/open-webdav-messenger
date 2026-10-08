package org.openwebdav.messenger.membership

/** A canonical, signed identity claim recovered only after chat-key AEAD authentication. */
internal class PrivateMembershipClaim(
    val chatId: String,
    val displayName: String,
    signingPublicKey: ByteArray,
    boxPublicKey: ByteArray,
) {
    private val signingKey = signingPublicKey.copyOf()
    private val boxKey = boxPublicKey.copyOf()

    fun copySigningPublicKey(): ByteArray = signingKey.copyOf()

    fun copyBoxPublicKey(): ByteArray = boxKey.copyOf()
}

/** Name provenance displayed to users; it is not an authorization or community-access grant. */
internal enum class MembershipIdentityProvenance {
    COMMUNITY_DIRECTORY,
    PRIVATE_CHAT_ONLY,
}

internal data class VerifiedPrivateMember(
    val claim: PrivateMembershipClaim,
    val displayName: String,
    val provenance: MembershipIdentityProvenance,
)
