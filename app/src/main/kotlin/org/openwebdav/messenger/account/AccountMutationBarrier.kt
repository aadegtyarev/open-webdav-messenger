package org.openwebdav.messenger.account

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes account replacement against polls, credential rotation, and other persistent mutations. */
internal class AccountMutationBarrier {
    private val mutex = Mutex()

    suspend fun <T> withExclusive(block: suspend () -> T): T = mutex.withLock { block() }

    companion object {
        val process = AccountMutationBarrier()
    }
}
