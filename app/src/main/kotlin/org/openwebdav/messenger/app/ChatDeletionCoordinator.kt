package org.openwebdav.messenger.app

import org.openwebdav.messenger.data.ChatDeletionCommitResult
import org.openwebdav.messenger.data.ChatDeletionEntity

internal class ChatDeletionCoordinator(
    private val commit: suspend (ChatDeletionEntity) -> ChatDeletionCommitResult,
    private val load: suspend (ChatDeletionEntity) -> ChatDeletionEntity?,
    private val transition: suspend (ChatDeletionEntity, String, String) -> Boolean,
    private val isCurrent: () -> Boolean,
    private val fenceGraph: () -> Unit,
    private val publish: suspend (ChatDeletionEntity) -> Boolean,
    private val cleanup: suspend (ChatDeletionEntity) -> Boolean,
) {
    suspend fun execute(deletion: ChatDeletionEntity): ChatDeletionStatus {
        if (!isCurrent()) return ChatDeletionStatus.STALE
        when (commit(deletion)) {
            ChatDeletionCommitResult.Conflict -> return ChatDeletionStatus.CONFLICT
            ChatDeletionCommitResult.Committed, ChatDeletionCommitResult.AlreadyCommitted -> Unit
        }
        fenceGraph()
        var current = load(deletion) ?: return ChatDeletionStatus.CONFLICT
        if (current.phase == ChatDeletionEntity.PHASE_LOCAL_COMMITTED) {
            if (!isCurrent() || !publish(current)) return ChatDeletionStatus.LOCAL_COMMITTED
            if (!isCurrent()) return ChatDeletionStatus.LOCAL_COMMITTED
            if (!transition(current, current.phase, ChatDeletionEntity.PHASE_TOMBSTONE_PUBLISHED)) {
                current = load(current) ?: return ChatDeletionStatus.TOMBSTONE_PUBLISHED
            } else {
                current = current.copy(phase = ChatDeletionEntity.PHASE_TOMBSTONE_PUBLISHED)
            }
        }
        if (current.phase == ChatDeletionEntity.PHASE_TOMBSTONE_PUBLISHED) {
            if (!transition(current, current.phase, ChatDeletionEntity.PHASE_CLEANUP_PENDING)) {
                current = load(current) ?: return ChatDeletionStatus.TOMBSTONE_PUBLISHED
            } else {
                current = current.copy(phase = ChatDeletionEntity.PHASE_CLEANUP_PENDING)
            }
        }
        if (current.phase == ChatDeletionEntity.PHASE_CLEANUP_PENDING) {
            if (!isCurrent() || !cleanup(current) || !isCurrent()) return ChatDeletionStatus.CLEANUP_PENDING
            if (!transition(current, current.phase, ChatDeletionEntity.PHASE_COMPLETE)) return ChatDeletionStatus.CLEANUP_PENDING
            current = current.copy(phase = ChatDeletionEntity.PHASE_COMPLETE)
        }
        return if (current.phase == ChatDeletionEntity.PHASE_COMPLETE) ChatDeletionStatus.COMPLETE else ChatDeletionStatus.CONFLICT
    }
}

enum class ChatDeletionStatus { STALE, CONFLICT, LOCAL_COMMITTED, TOMBSTONE_PUBLISHED, CLEANUP_PENDING, COMPLETE }
