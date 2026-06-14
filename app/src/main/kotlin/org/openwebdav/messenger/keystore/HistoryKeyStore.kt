package org.openwebdav.messenger.keystore

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.SecureRandom

/**
 * Device-local, Keystore-wrapped storage of the local message-history database encryption key
 * (`docs/architecture.md` decision "Local history encryption" / Security constraint SC17).
 *
 * The key is a **256-bit AES key** used by SQLCipher to encrypt the Room message-history database at
 * rest. It is generated once per device on first launch and never leaves the device — the raw key
 * resides only in memory long enough to construct the [net.sqlcipher.database.SupportFactory];
 * the on-disk blob is Keystore-wrapped and never in plaintext (SC4/SC5 family).
 *
 * The wrap/unwrap **mechanics** — Keystore AES/GCM wrapping key, `iv ‖ ct+tag` format, atomic write,
 * typed unwrap — live in the shared [KeystoreWrapper], under a **distinct alias**
 * (`owdm.history.wrap.v1`) and distinct directory (`history_key/`), so history-key operations never
 * disturb the chat-key or identity stores.
 *
 * **Policy:** the history database can be re-populated from the WebDAV disk (messages are re-fetched
 * on the next sync cycle), so an unrecoverable wrapped key is treated as **absent** — the old blob
 * is deleted and a fresh key is generated. This is the same re-derivable policy as [ChatKeyStore],
 * distinct from [IdentityStore] which must never silently regenerate.
 *
 * The random key is generated via [SecureRandom] (CSPRNG), not libsodium — a single 32-byte random
 * key from the OS CSPRNG needs no KDF and no external native library.
 *
 * Android-only — exercised by `connectedAndroidTest` (Keystore is device-backed; cannot run on the JVM).
 */
class HistoryKeyStore(context: Context) {
    /**
     * Return the 32-byte AES-256 history encryption key, generating and Keystore-wrapping it on first
     * access. If the stored wrapped blob exists but is unrecoverable (corrupt / Keystore key
     * invalidated), the corrupt blob is deleted and a fresh key is generated — the history database
     * will be empty (messages are re-fetchable from the WebDAV disk).
     *
     * The caller **owns** the returned byte array and MUST zeroize it after use (the
     * [net.zetetic.database.sqlcipher.SupportFactory] clones the bytes internally, so zeroization is
     * safe immediately after construction).
     *
     * Blocking — Keystore access must not be called on the main thread.
     */
    fun getOrCreateKey(): ByteArray {
        return when (val result = wrapper.unwrap()) {
            is UnwrapResult.Unwrapped -> result.plaintext // existing key, already unwrapped
            is UnwrapResult.None -> generateAndStore()
            is UnwrapResult.Unrecoverable -> {
                // Corrupt or invalidated — delete the bad blob and generate fresh.
                // The history DB will be re-created empty; messages re-fetch on next sync.
                wrapper.delete()
                generateAndStore()
            }
        }
    }

    /** Whether a wrapped key blob exists (it may still be unrecoverable — see [getOrCreateKey]). */
    fun has(): Boolean = wrapper.exists()

    /** Delete the stored wrapped key (e.g. on an explicit data-reset). */
    fun remove() {
        wrapper.delete()
    }

    /** The raw wrapped-on-disk bytes — for tests asserting the raw key is NOT in plaintext. */
    internal fun rawStoredBlob(): ByteArray? = wrapper.rawBlob()

    private fun generateAndStore(): ByteArray {
        val key = ByteArray(KEY_BYTES)
        SecureRandom.getInstanceStrong().nextBytes(key)
        try {
            wrapper.wrap(key)
        } catch (e: Exception) {
            key.fill(0)
            throw IOException("Failed to persist history encryption key", e)
        }
        return key
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
        /** Distinct from `ChatKeyStore`'s `owdm.chatkey.wrap.v1` and `IdentityStore`'s `owdm.identity.wrap.v1`. */
        private const val WRAP_KEY_ALIAS = "owdm.history.wrap.v1"

        /** Distinct directory from `chatkeys` and `identity` so the three stores never collide. */
        private const val KEY_DIR = "history_key"

        private const val KEY_FILE = "history_key.bin"

        /** AES-256 key size in bytes. */
        private const val KEY_BYTES = 32
    }
}
