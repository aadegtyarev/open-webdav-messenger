package org.openwebdav.messenger.keystore

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.NativeCrypto
import org.openwebdav.messenger.protocol.Base32
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device-local, Keystore-wrapped storage of per-chat [ChatKey]s
 * (`docs/architecture.md` decision 9 / Security constraints; `docs/stack-notes.md` → Android Keystore).
 *
 * The raw key is wrapped with a **non-exportable AES/GCM key generated in the Android Keystore**
 * (`KeyGenParameterSpec`, `androidx.security:security-crypto` is deprecated — not used) and only the
 * wrapped blob (`iv(12) ‖ ciphertext+tag`) is written to app-private internal storage. The raw key,
 * the passphrase, and the wrapping key **never** reach the WebDAV disk and are never logged.
 *
 * Android-only — exercised by `connectedAndroidTest` (Keystore is device-backed; cannot run on the JVM).
 */
class ChatKeyStore(
    private val context: Context,
    private val native: NativeCrypto,
) {
    /**
     * Wrap [chatKey] under the Keystore key and persist the wrapped blob for [chatId]. Overwrites any
     * existing stored key for that chat. The raw key bytes are zeroized after wrapping.
     */
    fun store(
        chatId: String,
        chatKey: ChatKey,
    ) {
        val raw = chatKey.copyBytes()
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
            val iv = cipher.iv
            val wrapped = cipher.doFinal(raw)
            val blob = ByteArray(iv.size + wrapped.size)
            iv.copyInto(blob, 0)
            wrapped.copyInto(blob, iv.size)
            keyFile(chatId).writeBytes(blob)
        } finally {
            raw.fill(0)
        }
    }

    /**
     * Load and unwrap the stored key for [chatId], or `null` if none is stored. The returned [ChatKey]
     * holds the raw bytes only in memory; the on-disk blob stays wrapped.
     */
    fun load(chatId: String): ChatKey? {
        val file = keyFile(chatId)
        if (!file.exists()) return null
        val blob = file.readBytes()
        if (blob.size <= IV_BYTES) return null
        val iv = blob.copyOfRange(0, IV_BYTES)
        val wrapped = blob.copyOfRange(IV_BYTES, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val raw = cipher.doFinal(wrapped)
        try {
            return ChatKey.fromBytes(raw)
        } finally {
            raw.fill(0)
        }
    }

    /** Whether a wrapped key is stored for [chatId]. */
    fun has(chatId: String): Boolean = keyFile(chatId).exists()

    /** Delete the stored wrapped key for [chatId] (e.g. on leaving a chat). */
    fun remove(chatId: String) {
        keyFile(chatId).delete()
    }

    /** The raw wrapped-on-disk bytes for [chatId] — for tests asserting the raw key is NOT in plaintext. */
    internal fun rawStoredBlob(chatId: String): ByteArray? = keyFile(chatId).takeIf { it.exists() }?.readBytes()

    private fun keyFile(chatId: String): File {
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

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(WRAP_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec =
            KeyGenParameterSpec.Builder(
                WRAP_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(WRAP_KEY_BITS)
                .build()
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val WRAP_KEY_ALIAS = "owdm.chatkey.wrap.v1"
        private const val KEY_DIR = "chatkeys"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val WRAP_KEY_BITS = 256
        private const val IV_BYTES = 12
        private const val GCM_TAG_BITS = 128

        /**
         * BLAKE2b digest length for the filename token. 16 bytes = 128 bits is far beyond collision
         * risk for the per-device set of chat-ids while keeping the name short. Base32 of 16 bytes is
         * 26 chars; [TOKEN_CHARS] = 26 keeps the full digest (no truncation below the encoded length).
         */
        private const val TOKEN_HASH_BYTES = 16
        private const val TOKEN_CHARS = 26
    }
}
