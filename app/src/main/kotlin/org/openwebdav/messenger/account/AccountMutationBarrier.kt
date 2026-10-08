package org.openwebdav.messenger.account

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/** Serializes account replacement against polls, credential rotation, and other persistent mutations. */
internal class AccountMutationBarrier {
    private val mutex = Mutex()
    private val accountReplacementMutex = Mutex()
    private val accountReplacementGeneration = AtomicLong()
    private val communityRotationMutex = KeyedMutex()

    suspend fun <T> withExclusive(block: suspend () -> T): T = mutex.withLock { block() }

    /** Serializes complete credential-rotation operations for one owner without retaining idle keys. */
    suspend fun <T> withCommunityCredentialRotation(
        ownerCommunityId: String,
        block: suspend () -> T,
    ): T = communityRotationMutex.withLock(ownerCommunityId, block)

    /** Serializes local account reads/writes and open/install with replacement, but never gates network I/O. */
    suspend fun <T> withStableAccount(block: suspend () -> T): T = accountReplacementMutex.withLock { block() }

    /** Serializes store replacement and runtime installation against all stable-account operations. */
    suspend fun <T> withAccountReplacement(block: suspend AccountReplacementScope.() -> T): T =
        accountReplacementMutex.withLock {
            val transaction = AccountReplacementScope(accountReplacementGeneration)
            try {
                transaction.block()
            } finally {
                transaction.finish()
            }
        }

    /** Makes the new account generation visible to local runtime installation before releasing the gate. */
    internal class AccountReplacementScope internal constructor(private val generation: AtomicLong) {
        private var committedGeneration: Long? = null

        fun commitGeneration(): Long = committedGeneration ?: generation.incrementAndGet().also { committedGeneration = it }

        internal fun finish() {
            if (committedGeneration == null) commitGeneration()
        }
    }

    fun replacementGeneration(): Long = accountReplacementGeneration.get()

    private class KeyedMutex {
        private class Entry {
            val mutex = Mutex()
            var references = 0
        }

        private val guard = Any()
        private val entries = mutableMapOf<String, Entry>()

        suspend fun <T> withLock(
            key: String,
            block: suspend () -> T,
        ): T {
            val entry =
                synchronized(guard) {
                    entries.getOrPut(key, ::Entry).also { it.references++ }
                }
            try {
                return entry.mutex.withLock { block() }
            } finally {
                synchronized(guard) {
                    entry.references--
                    if (entry.references == 0 && entries[key] === entry) entries.remove(key)
                }
            }
        }
    }

    companion object {
        val process = AccountMutationBarrier()
    }
}
