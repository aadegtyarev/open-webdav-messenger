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
class CommunityKeyStore(context: Context) : ExportableCommunityKeyStore {
    /** Wrap and persist [key]. Overwrites any existing stored key atomically. */
    override fun store(key: ChatKey) {
        val raw = key.export()
        try {
            wrapper.wrap(raw)
        } finally {
            raw.fill(0)
        }
    }

    /** Load and unwrap the stored key, or null if none stored or blob is unrecoverable. */
    override fun load(): ChatKey? {
        return when (val result = wrapper.unwrap()) {
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
    fun has(): Boolean = wrapper.exists()

    /** Delete the stored key. */
    fun remove() {
        wrapper.delete()
    }

    private val wrapper =
        KeystoreWrapper(
            WRAP_KEY_ALIAS,
            File(context.filesDir, KEY_DIR).let { dir ->
                dir.mkdirs()
                File(dir, KEY_FILE)
            },
        )

    private companion object {
        /** Distinct alias from chat-key, identity, history, and connection-config. */
        private const val WRAP_KEY_ALIAS = "owdm.communitykey.wrap.v1"

        private const val KEY_DIR = "community_key"
        private const val KEY_FILE = "community_key.bin"
    }
}
