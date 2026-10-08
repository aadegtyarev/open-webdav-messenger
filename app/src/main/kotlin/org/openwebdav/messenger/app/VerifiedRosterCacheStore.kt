package org.openwebdav.messenger.app

import android.content.Context
import org.openwebdav.messenger.keystore.KeystoreWrapper
import org.openwebdav.messenger.keystore.StrictFileOperations
import org.openwebdav.messenger.keystore.UnwrapResult
import java.io.File

internal class VerifiedRosterCacheStore(
    context: Context,
    private val atomicReplace: (File, File) -> Unit = { source, target ->
        StrictFileOperations.atomicReplace(source, target)
    },
) : VerifiedRosterCachePersistence {
    private val file = File(context.filesDir, "verified_roster/rosters.bin")
    private val wrapper get() = KeystoreWrapper(KEY_ALIAS, file, atomicReplace)

    override fun load(
        communityId: String,
        chatId: String,
    ): CachedVerifiedRoster? =
        synchronized(LOCK) {
            readAll()?.firstOrNull { it.communityId == communityId && it.chatId == chatId }
        }

    override fun put(entry: CachedVerifiedRoster) =
        synchronized(LOCK) {
            val entries = readAll().orEmpty().filterNot { it.communityId == entry.communityId && it.chatId == entry.chatId }
            wrapper.wrapStrictAtomic(RosterCacheCodec.encode(entries + entry))
        }

    override fun remove(
        communityId: String,
        chatId: String,
    ) = synchronized(LOCK) {
        val entries = readAll() ?: return@synchronized clearLocked()
        val remaining = entries.filterNot { it.communityId == communityId && it.chatId == chatId }
        if (remaining.size == entries.size) return@synchronized
        if (remaining.isEmpty()) clearLocked() else wrapper.wrapStrictAtomic(RosterCacheCodec.encode(remaining))
    }

    override fun clear() = synchronized(LOCK) { clearLocked() }

    private fun readAll(): List<CachedVerifiedRoster>? {
        if (!file.exists()) return null
        if (file.length() > RosterCacheCodec.MAX_FILE_BYTES + WRAPPER_OVERHEAD) {
            runCatching { clearLocked() }
            return null
        }
        return when (val result = runCatching { wrapper.unwrap() }.getOrNull()) {
            is UnwrapResult.Unwrapped -> {
                val decoded = result.plaintext.useBytes(RosterCacheCodec::decode)
                if (decoded == null) runCatching { clearLocked() }
                decoded
            }
            is UnwrapResult.None, is UnwrapResult.Unrecoverable, null -> {
                runCatching { clearLocked() }
                null
            }
        }
    }

    private fun clearLocked() {
        try {
            wrapper.delete()
        } finally {
            wrapper.destroyWrappingKey()
        }
    }

    private fun ByteArray.useBytes(block: (ByteArray) -> List<CachedVerifiedRoster>?): List<CachedVerifiedRoster>? =
        try {
            block(this)
        } finally {
            fill(0)
        }

    private companion object {
        const val KEY_ALIAS = "owdm.verified-roster.wrap.v1"
        const val WRAPPER_OVERHEAD = 28L
        val LOCK = Any()
    }
}
