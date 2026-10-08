package org.openwebdav.messenger.app

/** Invalidates asynchronous chat opens when a newer community/runtime selection begins. */
internal class RuntimeSelectionGuard(
    private val commitCoordinator: RosterCommitCoordinator = RosterCommitCoordinator(),
) {
    private val lock = Any()
    private var revision = 0L

    fun current(): Long = synchronized(lock) { revision }

    fun begin(): Long = commitCoordinator.serialized { synchronized(lock) { ++revision } }

    fun isCurrent(expectedRevision: Long): Boolean = synchronized(lock) { revision == expectedRevision }

    fun runIfCurrent(
        expectedRevision: Long,
        install: () -> Boolean,
    ): Boolean =
        synchronized(lock) {
            if (revision != expectedRevision) return@synchronized false
            install()
        }
}
