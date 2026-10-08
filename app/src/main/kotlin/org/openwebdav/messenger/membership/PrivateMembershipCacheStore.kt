package org.openwebdav.messenger.membership

import android.content.Context
import org.openwebdav.messenger.keystore.KeystoreWrapper
import org.openwebdav.messenger.keystore.StrictFileOperations
import org.openwebdav.messenger.keystore.UnwrapResult
import java.io.File

/** Keystore-encrypted, Android-backup-excluded cache; remote claims are not exported. */
internal class PrivateMembershipCacheStore(
    context: Context,
    private val atomicReplace: (File, File) -> Unit = StrictFileOperations::atomicReplace,
) : PrivateMembershipCachePersistence {
    private val file = File(context.noBackupFilesDir, "private_membership/cache.bin")
    private val wrapper get() = KeystoreWrapper(KEY_ALIAS, file, atomicReplace)

    override fun loadAll(): List<PrivateMembershipCacheRecord>? =
        synchronized(LOCK) {
            if (!file.exists()) return@synchronized null
            if (file.length() > PrivateMembershipCacheCodec.MAX_BYTES + WRAPPER_OVERHEAD) return@synchronized clearAndMiss()
            val result = runCatching { wrapper.unwrap() }.getOrNull()
            if (result !is UnwrapResult.Unwrapped) return@synchronized clearAndMiss()
            val plaintext = result.plaintext
            try {
                PrivateMembershipCacheCodec.decode(plaintext).also { if (it == null) clearAndMiss() }
            } finally {
                plaintext.fill(0)
            }
        }

    override fun replaceAll(records: List<PrivateMembershipCacheRecord>) {
        synchronized(LOCK) {
            if (records.isEmpty()) clearAndMiss() else wrapper.wrapStrictAtomic(PrivateMembershipCacheCodec.encode(records))
        }
    }

    override fun clear() {
        synchronized(LOCK) { clearAndMiss() }
    }

    private fun clearAndMiss(): Nothing? {
        try {
            wrapper.delete()
        } finally {
            wrapper.destroyWrappingKey()
        }
        return null
    }

    private companion object {
        const val KEY_ALIAS = "owdm.private-membership-cache.v1"
        const val WRAPPER_OVERHEAD = 28L
        val LOCK = Any()
    }
}
