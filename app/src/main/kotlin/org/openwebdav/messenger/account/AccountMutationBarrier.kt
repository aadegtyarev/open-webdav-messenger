package org.openwebdav.messenger.account

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/** Serializes account replacement against polls, credential rotation, and other persistent mutations. */
internal class AccountMutationBarrier {
    private val mutex = Mutex()
    private val accountReplacementMutex = Mutex()
    private val accountReplacementGeneration = AtomicLong()

    suspend fun <T> withExclusive(block: suspend () -> T): T = mutex.withLock { block() }

    /** Serializes local open/install with account replacement, but not with network polling. */
    suspend fun <T> withStableAccount(block: suspend () -> T): T = accountReplacementMutex.withLock { block() }

    /** Protects account-store/runtime writes and invalidates snapshots captured before this mutation. */
    suspend fun <T> withAccountReplacement(block: suspend () -> T): T =
        accountReplacementMutex.withLock {
            try {
                block()
            } finally {
                accountReplacementGeneration.incrementAndGet()
            }
        }

    fun replacementGeneration(): Long = accountReplacementGeneration.get()

    companion object {
        val process = AccountMutationBarrier()
    }
}
