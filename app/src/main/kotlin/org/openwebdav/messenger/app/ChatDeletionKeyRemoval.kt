package org.openwebdav.messenger.app

internal class ChatDeletionKeyRemoval(private val currentGeneration: () -> Long) {
    /** Caller holds the stable-account barrier; this checks the captured generation and exact context. */
    fun removeLocked(
        expectedGeneration: Long,
        contextIsCurrent: () -> Boolean,
        removeIfUnreferenced: () -> Boolean,
    ): Boolean = expectedGeneration == currentGeneration() && contextIsCurrent() && removeIfUnreferenced()
}
