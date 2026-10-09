package org.openwebdav.messenger.app

internal enum class ChatListDeletionResult {
    LOCAL_DELETED,
    REMOTE_LOCAL_COMMITTED,
    REMOTE_TOMBSTONE_PUBLISHED,
    REMOTE_CLEANUP_PENDING,
    REMOTE_COMPLETE,
    FAILED,
}
