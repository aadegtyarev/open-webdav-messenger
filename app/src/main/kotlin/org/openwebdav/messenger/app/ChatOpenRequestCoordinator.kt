package org.openwebdav.messenger.app

/** Orders opens at dispatch; guarded actions are synchronous so request issue cannot interleave with mutation. */
internal class ChatOpenRequestCoordinator(
    private val commitCoordinator: RosterCommitCoordinator = RosterCommitCoordinator(),
) {
    class Token internal constructor(
        internal val owner: ChatOpenRequestCoordinator,
        internal val generation: Long,
    )

    private val lock = Any()
    private var generation = 0L

    fun begin(onSuperseded: () -> Unit = {}): Token =
        commitCoordinator.serialized {
            synchronized(lock) {
                val token = Token(this, ++generation)
                onSuperseded()
                token
            }
        }

    fun isCurrent(token: Token): Boolean = synchronized(lock) { token.owner === this && token.generation == generation }

    fun runIfCurrent(
        token: Token,
        action: () -> Boolean,
    ): Boolean =
        synchronized(lock) {
            if (token.owner !== this || token.generation != generation) return@synchronized false
            action()
        }

    /** Run a request-guarded selection/runtime mutation in the common cache→coordinator→request order. */
    fun runIfCurrentSerialized(
        token: Token,
        action: () -> Boolean,
    ): Boolean =
        commitCoordinator.serialized {
            synchronized(lock) {
                if (token.owner !== this || token.generation != generation) return@synchronized false
                action()
            }
        }
}
