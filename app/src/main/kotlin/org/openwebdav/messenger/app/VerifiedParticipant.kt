package org.openwebdav.messenger.app

import org.openwebdav.messenger.directory.DirectoryEntry
import org.openwebdav.messenger.protocol.Hex
import java.security.MessageDigest

/** Public, display-safe projection of a verified directory row; contains no box or secret key material. */
internal data class VerifiedParticipant(
    val displayName: String,
    val identityDigest: String,
    val isSelf: Boolean,
    val provenance: ParticipantIdentityProvenance = ParticipantIdentityProvenance.COMMUNITY_DIRECTORY,
)

internal fun verifiedParticipants(
    entries: List<DirectoryEntry>,
    senderIdentifier: String,
    senderSigningPublicKey: ByteArray,
): List<VerifiedParticipant> {
    val participants =
        entries.map { entry ->
            val signingKey = entry.copySigningPublicKey()
            VerifiedParticipant(
                displayName = entry.displayName,
                identityDigest = participantDigest(signingKey),
                isSelf = Hex.encode(signingKey) == senderIdentifier,
            )
        }
    if (participants.any { it.isSelf }) return participants
    return participants +
        VerifiedParticipant(
            displayName = "",
            identityDigest = participantDigest(senderSigningPublicKey),
            isSelf = true,
        )
}

/** A full domain-separated digest of the verified public signing identity; UI renders only a short prefix. */
internal fun withSelfParticipant(
    participants: List<VerifiedParticipant>,
    senderSigningPublicKey: ByteArray,
    selfProvenance: ParticipantIdentityProvenance = ParticipantIdentityProvenance.COMMUNITY_DIRECTORY,
): List<VerifiedParticipant> =
    if (participants.any { it.isSelf }) {
        participants.toList()
    } else {
        participants +
            VerifiedParticipant(
                displayName = "",
                identityDigest = participantDigest(senderSigningPublicKey),
                isSelf = true,
                provenance = selfProvenance,
            )
    }

internal fun participantDigest(signingPublicKey: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(FINGERPRINT_DOMAIN)
    digest.update(signingPublicKey)
    return Hex.encode(digest.digest())
}

private val FINGERPRINT_DOMAIN = "OWDM participant signing identity v1\u0000".toByteArray(Charsets.UTF_8)
