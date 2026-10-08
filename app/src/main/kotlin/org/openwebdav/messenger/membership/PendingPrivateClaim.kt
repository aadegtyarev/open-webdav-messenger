package org.openwebdav.messenger.membership

internal data class PendingPrivateClaim(
    val fileBytes: ByteArray,
    val uploaded: Boolean,
)
