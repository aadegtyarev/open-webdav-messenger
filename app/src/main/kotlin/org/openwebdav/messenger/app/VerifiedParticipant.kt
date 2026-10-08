package org.openwebdav.messenger.app

import org.openwebdav.messenger.directory.DirectoryEntry
import org.openwebdav.messenger.protocol.Hex
import java.security.MessageDigest

/** Public, display-safe projection of a verified directory row; contains no box or secret key material. */
internal data class VerifiedParticipant(
    val displayName: String,
    val fingerprint: String,
    val isSelf: Boolean,
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
                fingerprint = participantFingerprint(signingKey),
                isSelf = Hex.encode(signingKey) == senderIdentifier,
            )
        }
    if (participants.any { it.isSelf }) return participants
    return participants +
        VerifiedParticipant(
            displayName = "",
            fingerprint = participantFingerprint(senderSigningPublicKey),
            isSelf = true,
        )
}

/** A short, domain-separated digest of the verified public signing identity. */
internal fun withSelfParticipant(
    participants: List<VerifiedParticipant>,
    senderSigningPublicKey: ByteArray,
): List<VerifiedParticipant> =
    if (participants.any { it.isSelf }) {
        participants.toList()
    } else {
        participants +
            VerifiedParticipant(
                displayName = "",
                fingerprint = participantFingerprint(senderSigningPublicKey),
                isSelf = true,
            )
    }

internal fun participantFingerprint(signingPublicKey: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(FINGERPRINT_DOMAIN)
    digest.update(signingPublicKey)
    return Hex.encode(digest.digest()).take(FINGERPRINT_HEX_LENGTH)
}

private val FINGERPRINT_DOMAIN = "OWDM participant signing identity v1\u0000".toByteArray(Charsets.UTF_8)
private const val FINGERPRINT_HEX_LENGTH = 10
