package org.openwebdav.messenger.app

/**
 * Serializes roster commits with request/selection issuance. A commit holds cache state then this
 * coordinator, preflights guards, performs disk I/O without request/selection locks, then applies under
 * request → selection → runtime locks. Guard issuance takes this coordinator before its guard lock; a
 * commit never begins while already holding a request/selection guard.
 */
internal class RosterCommitCoordinator {
    private val lock = Any()

    fun <T> serialized(block: () -> T): T = synchronized(lock, block)
}
