package org.openwebdav.messenger.app

import org.openwebdav.messenger.directory.DirectoryEntry

data class CachedVerifiedRoster(
    val communityId: String,
    val chatId: String,
    val provenance: ByteArray,
    val entries: List<DirectoryEntry>,
)
