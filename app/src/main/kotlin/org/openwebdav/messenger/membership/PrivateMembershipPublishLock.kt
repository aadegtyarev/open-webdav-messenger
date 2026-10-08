package org.openwebdav.messenger.membership

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes same-context foreground and background claim publication attempts. */
internal class PrivateMembershipPublishLock {
    private data class Entry(val mutex: Mutex, var references: Int = 0)

    private val guard = Any()
    private val entries = mutableMapOf<String, Entry>()

    suspend fun <T> withLock(
        key: String,
        block: suspend () -> T,
    ): T {
        val entry = synchronized(guard) { entries.getOrPut(key) { Entry(Mutex()) }.also { it.references++ } }
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
