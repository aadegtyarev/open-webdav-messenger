package org.openwebdav.messenger.app

import org.openwebdav.messenger.transport.WebDavResult

/** Observable result for a community policy write; only [Saved] represents committed remote state. */
internal sealed interface CommunityMetadataUpdate {
    data object Saved : CommunityMetadataUpdate

    data object Superseded : CommunityMetadataUpdate

    data class Rejected(val transport: WebDavResult<Unit>) : CommunityMetadataUpdate

    data class Failed(val message: String) : CommunityMetadataUpdate
}
