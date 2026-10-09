package org.openwebdav.messenger.app

import org.openwebdav.messenger.directory.DirectoryEntry

internal data class PreparedDmStart(
    val graph: RuntimeGraph,
    val peer: DirectoryEntry,
    val chatId: String,
    val communityId: String,
    val generation: Long,
    val existingDm: Boolean,
    val skipGovernance: Boolean,
)
