package org.openwebdav.messenger.app

import org.openwebdav.messenger.crypto.ChatKey

internal object ChatDeletionRetryKey {
    fun select(
        scope: String,
        phase: String,
        communityKey: ChatKey,
        loadDmKey: () -> ChatKey?,
    ): ChatKey? =
        if (scope == org.openwebdav.messenger.data.ChatDeletionEntity.SCOPE_DM &&
            phase == org.openwebdav.messenger.data.ChatDeletionEntity.PHASE_LOCAL_COMMITTED
        ) {
            loadDmKey()
        } else {
            communityKey
        }
}
