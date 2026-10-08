package org.openwebdav.messenger.keystore

import android.content.Context
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.NativeCrypto
import org.openwebdav.messenger.export.ExportableChatKeyStore
import org.openwebdav.messenger.protocol.Base32
import java.io.File

/**
 * Device-local, Keystore-wrapped storage of per-chat [ChatKey]s
 * (`docs/architecture.md` decision 9 / Security constraints; `docs/stack-notes.md` → Android Keystore).
 *
 * The wrap/unwrap **mechanics** (Keystore AES/GCM key, `iv ‖ ct+tag` format, atomic write, typed unwrap)
 * live in the shared [KeystoreWrapper]; this store keeps only the per-chat **policy** and file naming.
 * The raw key, the passphrase, and the wrapping key **never** reach the WebDAV disk and are never logged.
 *
 * **Policy:** a chat key is re-derivable (passphrase) / re-agreeable, so an unrecoverable wrapped blob is
 * treated as **absent** ([load] returns `null`) — distinct from `IdentityStore`, which must surface an
 * unrecoverable identity (account loss) and never regenerate.
 *
 * Android-only — exercised by `connectedAndroidTest` (Keystore is device-backed; cannot run on the JVM).
 */
class ChatKeyStore(
    private val context: Context,
    private val native: NativeCrypto,
) : ChatKeyStorePort, ExportableChatKeyStore {
    /**
     * Wrap [chatKey] under the Keystore key and persist the wrapped blob for [chatId]. Overwrites any
     * existing stored key for that chat (atomic write). The raw key bytes are zeroized after wrapping.
     * Also records the chatId in the sidecar index so [listChatIds] can enumerate stored keys.
     */
    override fun store(
        chatId: String,
        chatKey: ChatKey,
    ) = storeStrict(chatId, chatKey)

    override fun storeStrict(
        chatId: String,
        chatKey: ChatKey,
    ) {
        val raw = chatKey.copyBytes()
        val keyWrapper = wrapper(chatId)
        val keyWasPresent = keyWrapper.exists()
        try {
            keyWrapper.wrap(raw)
            try {
                index().add(chatId)
            } catch (failure: Exception) {
                if (!keyWasPresent) {
                    try {
                        keyWrapper.delete()
                    } catch (cleanupFailure: Exception) {
                        failure.addSuppressed(cleanupFailure)
                    }
                }
                throw failure
            }
        } finally {
            raw.fill(0)
        }
    }

    /**
     * Load and unwrap the stored key for [chatId], or `null` if none is stored **or** the stored blob is
     * unrecoverable (corrupt / Keystore key invalidated). A chat key is re-derivable, so an unrecoverable
     * blob is treated as absent here — the policy difference from `IdentityStore`. The returned [ChatKey]
     * holds the raw bytes only in memory; the on-disk blob stays wrapped.
     */
    override fun load(chatId: String): ChatKey? =
        when (val result = wrapper(chatId).unwrap()) {
            is UnwrapResult.None -> null
            is UnwrapResult.Unrecoverable -> null // re-derivable: treat as absent (policy)
            is UnwrapResult.Unwrapped -> {
                val raw = result.plaintext
                try {
                    ChatKey.fromBytes(raw)
                } finally {
                    raw.fill(0)
                }
            }
        }

    /**
     * List indexed chat-ids with an existing wrapped key. Index read failures propagate so account
     * replacement cannot mistake an unreadable index for an empty key store. No secret material lives
     * in the index (chat-ids are not secret).
     */
    override fun listChatIds(): List<String> {
        return index().list(::has)
    }

    /** Whether a wrapped key is stored for [chatId]. */
    fun has(chatId: String): Boolean = wrapper(chatId).exists()

    /** Delete the stored wrapped key for [chatId] (e.g. on leaving a chat). */
    override fun remove(chatId: String) = removeStrict(chatId)

    override fun removeStrict(chatId: String) {
        wrapper(chatId).delete()
        index().remove(chatId)
    }

    override fun replaceAll(chatKeys: Map<String, ChatKey>) = replaceAllStrict(chatKeys)

    override fun replaceAllStrict(chatKeys: Map<String, ChatKey>) {
        val operations = StrictOperationBatch()
        (listChatIds() - chatKeys.keys).forEach { id -> operations.run { removeStrict(id) } }
        chatKeys.forEach { (chatId, key) -> operations.run { storeStrict(chatId, key) } }
        operations.finish()
    }

    /** The raw wrapped-on-disk bytes for [chatId] — for tests asserting the raw key is NOT in plaintext. */
    internal fun rawStoredBlob(chatId: String): ByteArray? = wrapper(chatId).rawBlob()

    private fun wrapper(chatId: String): KeystoreWrapper = KeystoreWrapper(WRAP_KEY_ALIAS, keyFile(chatId))

    private fun keyFile(chatId: String): File {
        AccountIdentifier.requireValid(chatId)
        val dir = File(context.filesDir, KEY_DIR).apply { mkdirs() }
        // The on-disk file name is a collision-resistant token of the chat-id, NOT a hand-rolled
        // polynomial fold (which collides — two distinct chat-ids could overwrite each other's
        // wrapped key and lose access to a chat). The chat-id is not secret, so this is just a
        // stable, filename-safe token: BLAKE2b(chat-id) via the same genericHash primitive used in
        // KeySources.saltForChat / knownKey, Base32-lowercase-encoded and length-bounded.
        val digest = native.genericHash(chatId.toByteArray(Charsets.UTF_8), TOKEN_HASH_BYTES)
        val token = Base32.encodeBase32Lower(digest).take(TOKEN_CHARS)
        return File(dir, "k_$token.bin")
    }

    // -- sidecar index: chat-id → filename-token mapping (not secret; plaintext on disk) -------

    private fun index(): ChatKeyIndex =
        File(context.filesDir, KEY_DIR).apply { mkdirs() }.let { dir ->
            ChatKeyIndex(File(dir, INDEX_FILE_NAME))
        }

    companion object {
        private const val WRAP_KEY_ALIAS = "owdm.chatkey.wrap.v1"
        private const val KEY_DIR = "chatkeys"
        private const val INDEX_FILE_NAME = "chat_ids.txt"

        /**
         * BLAKE2b digest length for the filename token. 16 bytes = 128 bits is far beyond collision
         * risk for the per-device set of chat-ids while keeping the name short. Base32 of 16 bytes is
         * 26 chars; [TOKEN_CHARS] = 26 keeps the full digest (no truncation below the encoded length).
         */
        private const val TOKEN_HASH_BYTES = 16
        private const val TOKEN_CHARS = 26
    }
}
