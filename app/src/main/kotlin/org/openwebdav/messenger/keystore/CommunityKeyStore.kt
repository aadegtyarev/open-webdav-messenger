package org.openwebdav.messenger.keystore

import android.content.Context
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.export.ExportableCommunityKeyStore
import java.io.File

/**
 * Device-local, Keystore-wrapped storage of the community-wide symmetric community key
 * (a 32-byte [ChatKey] — `docs/architecture.md` decisions 7/10, SC19).
 *
 * The community key gates access to the community directory and chat directory. It is shared
 * among all members of a community out-of-band. On device, it is Keystore-wrapped and never
 * in plaintext on disk.
 *
 * The wrap/unwrap mechanics live in the shared [KeystoreWrapper] under a distinct alias
 * ([WRAP_KEY_ALIAS]), separate from chat-key, identity, history, and connection-config stores.
 *
 * Android-only — exercised by connectedAndroidTest.
 */
class CommunityKeyStore(private val context: Context) : ExportableCommunityKeyStore {
    /** Wrap and persist the legacy default community key. */
    override fun store(key: ChatKey) = store("default", key)

    override fun store(
        communityId: String,
        key: ChatKey,
    ) {
        val raw = key.export()
        try {
            wrapper(communityId).wrap(raw)
        } finally {
            raw.fill(0)
        }
    }

    /** Load and unwrap the legacy default community key. */
    override fun load(): ChatKey? = load("default")

    override fun load(communityId: String): ChatKey? {
        return when (val result = wrapper(communityId).unwrap()) {
            is UnwrapResult.None -> null
            is UnwrapResult.Unrecoverable -> null
            is UnwrapResult.Unwrapped -> {
                val raw = result.plaintext
                try {
                    if (raw.size == ChatKey.KEY_BYTES) ChatKey.fromBytes(raw) else null
                } finally {
                    raw.fill(0)
                }
            }
        }
    }

    /** Whether a wrapped key blob exists. */
    fun has(): Boolean = wrapper("default").exists()

    /** Delete the stored legacy default key. */
    override fun clear() = remove("default")

    override fun remove(communityId: String) {
        wrapper(communityId).delete()
    }

    override fun listCommunityIds(): Set<String> = storedIds()

    override fun replaceAll(keys: Map<String, ChatKey>) = replaceAllStrict(keys)

    override fun replaceAllStrict(keys: Map<String, ChatKey>) {
        val operations = StrictOperationBatch()
        (storedIds() - keys.keys).forEach { id -> operations.run { remove(id) } }
        keys.forEach { (communityId, key) -> operations.run { store(communityId, key) } }
        operations.finish()
    }

    private fun wrapper(communityId: String): KeystoreWrapper {
        AccountIdentifier.requireValid(communityId)
        val fileName = if (communityId == "default") KEY_FILE else "${token(communityId)}.bin"
        val alias = if (communityId == "default") WRAP_KEY_ALIAS else "$WRAP_KEY_ALIAS.$communityId"
        val dir = File(context.filesDir, KEY_DIR).apply { mkdirs() }
        return KeystoreWrapper(alias, File(dir, fileName))
    }

    private fun storedIds(): Set<String> {
        val dir = File(context.filesDir, KEY_DIR)
        val ids =
            dir.listFiles()?.mapNotNull { file ->
                if (file.name == KEY_FILE) "default" else file.name.removeSuffix(".bin").let(::decodeToken)
            }.orEmpty()
        return ids.toSet()
    }

    private fun token(id: String): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(id.toByteArray())

    private fun decodeToken(token: String): String? =
        runCatching {
            String(java.util.Base64.getUrlDecoder().decode(token), Charsets.UTF_8).takeIf(AccountIdentifier::isValid)
        }.getOrNull()

    private companion object {
        /** Distinct alias from chat-key, identity, history, and connection-config. */
        private const val WRAP_KEY_ALIAS = "owdm.communitykey.wrap.v1"

        private const val KEY_DIR = "community_key"
        private const val KEY_FILE = "community_key.bin"
    }
}
