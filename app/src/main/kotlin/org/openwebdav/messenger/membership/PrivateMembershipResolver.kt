package org.openwebdav.messenger.membership

import org.openwebdav.messenger.directory.DirectoryEntry
import org.openwebdav.messenger.protocol.Hex

/** Deterministically deduplicates verified claims and conditionally upgrades identity provenance. */
internal object PrivateMembershipResolver {
    data class Result(val members: List<VerifiedPrivateMember>, val rejectedCount: Int)

    fun resolve(
        claims: List<PrivateMembershipClaim>,
        directoryEntries: List<DirectoryEntry>?,
    ): Result {
        val bySigner = claims.groupBy { Hex.encode(it.copySigningPublicKey()) }
        val directory = directoryEntries.orEmpty().groupBy { Hex.encode(it.copySigningPublicKey()) }
        var rejected = 0
        val members = mutableListOf<VerifiedPrivateMember>()
        for ((signer, copies) in bySigner.toSortedMap()) {
            val boxes = copies.map { Hex.encode(it.copyBoxPublicKey()) }.distinct()
            if (boxes.size != 1) {
                rejected += copies.size
                continue
            }
            val directoryCopies = directory[signer].orEmpty()
            val directoryBoxes = directoryCopies.map { Hex.encode(it.copyBoxPublicKey()) }.distinct()
            if (directoryBoxes.size > 1 || (directoryBoxes.isNotEmpty() && directoryBoxes.single() != boxes.single())) {
                rejected += copies.size
                continue
            }
            val claim = copies.minBy { it.displayName }
            val matched = directoryCopies.firstOrNull()
            members +=
                VerifiedPrivateMember(
                    claim = claim,
                    displayName = matched?.displayName ?: claim.displayName,
                    provenance =
                        if (matched == null) {
                            MembershipIdentityProvenance.PRIVATE_CHAT_ONLY
                        } else {
                            MembershipIdentityProvenance.COMMUNITY_DIRECTORY
                        },
                )
            rejected += copies.size - 1
        }
        return Result(members, rejected)
    }
}
