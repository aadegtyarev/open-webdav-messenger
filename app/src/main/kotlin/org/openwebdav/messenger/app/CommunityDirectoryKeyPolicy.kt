package org.openwebdav.messenger.app

import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.keystore.ChatRegistry

/** A community directory key is valid only through the exact durable General anchor. */
internal object CommunityDirectoryKeyPolicy {
    fun resolve(
        communityAnchorId: String?,
        storedAnchorId: String?,
        anchor: ChatRegistry.Entry?,
        communityKey: ChatKey?,
        anchorKey: ChatKey?,
    ): ChatKey? {
        if (communityAnchorId == null || storedAnchorId != communityAnchorId) return null
        if (anchor?.id != communityAnchorId || anchor.kind != "general" || anchor.access != "public") return null
        return communityKey ?: anchorKey
    }
}
