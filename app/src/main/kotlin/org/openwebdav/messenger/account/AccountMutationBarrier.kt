package org.openwebdav.messenger.account

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes account replacement against polls, credential rotation, and other persistent mutations. */
internal class AccountMutationBarrier {
    private val mutex = Mutex()
    private val accountReplacementMutex = Mutex()

    suspend fun <T> withExclusive(block: suspend () -> T): T = mutex.withLock { block() }

    /** Serializes local open/install with account replacement, but not with network polling. */
    suspend fun <T> withStableAccount(block: suspend () -> T): T = accountReplacementMutex.withLock { block() }

    /** Protects the account stores/runtime from open-time reads while they are replaced. */
    suspend fun <T> withAccountReplacement(block: suspend () -> T): T = accountReplacementMutex.withLock { block() }

    companion object {
        val process = AccountMutationBarrier()
    }
}
