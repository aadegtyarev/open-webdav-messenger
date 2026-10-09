package org.openwebdav.messenger.data

sealed interface ChatDeletionCommitResult {
    data object Committed : ChatDeletionCommitResult

    data object AlreadyCommitted : ChatDeletionCommitResult

    data object Conflict : ChatDeletionCommitResult
}
