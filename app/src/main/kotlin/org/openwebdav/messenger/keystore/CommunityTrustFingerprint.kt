package org.openwebdav.messenger.keystore

import org.openwebdav.messenger.protocol.Base32
import java.security.MessageDigest

internal object CommunityTrustFingerprint {
    fun of(signingPublicKey: ByteArray): String =
        Base32.encodeBase32Lower(MessageDigest.getInstance("SHA-256").digest(signingPublicKey)).take(FINGERPRINT_CHARS)

    private const val FINGERPRINT_CHARS = 10
}
