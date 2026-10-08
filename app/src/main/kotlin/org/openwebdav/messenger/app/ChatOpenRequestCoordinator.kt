package org.openwebdav.messenger.app

/** Orders opens at dispatch; guarded actions are synchronous so request issue cannot interleave with mutation. */
internal class ChatOpenRequestCoordinator {
    class Token internal constructor(
        internal val owner: ChatOpenRequestCoordinator,
        internal val generation: Long,
    )

    private val lock = Any()
    private var generation = 0L

    fun begin(): Token = synchronized(lock) { Token(this, ++generation) }

    fun isCurrent(token: Token): Boolean = synchronized(lock) { token.owner === this && token.generation == generation }

    fun runIfCurrent(
        token: Token,
        action: () -> Boolean,
    ): Boolean =
        synchronized(lock) {
            if (token.owner !== this || token.generation != generation) return@synchronized false
            action()
        }
}
