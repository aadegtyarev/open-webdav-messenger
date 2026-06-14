package org.openwebdav.messenger.export

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.NativeCrypto
import org.openwebdav.messenger.identity.IdentityLoadResult
import java.util.Base64

/**
 * Collects every device-local secret, serializes them into a [ExportPayload], encrypts the
 * payload under a user-supplied passphrase (Argon2id → XChaCha20-Poly1305), and returns a
 * base64 blob ready to share via Android's ACTION_SEND.
 *
 * The passphrase is mandatory — a device-bound Keystore key cannot be restored cross-device.
 * The passphrase [CharArray] is consumed and zeroized.
 *
 * @param ioDispatcher the dispatcher the blocking Argon2id KDF runs on (defaults to [Dispatchers.IO]).
 */
class ExportManager(
    private val native: NativeCrypto,
    private val connectionConfigStore: ExportableConnectionConfigStore,
    private val communityKeyStore: ExportableCommunityKeyStore,
    private val chatKeyStore: ExportableChatKeyStore,
    private val identityStore: ExportableIdentityStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Collect all device-local secrets, encrypt under [passphrase], and return a base64 blob.
     * The [passphrase] char array is consumed (zeroized) before this returns — callers must
     * not read it afterwards.
     *
     * Thread safety: the collection reads all stores on the calling thread, then the Argon2id
     * KDF runs on [ioDispatcher]. All store reads are blocking — callers must not call this
     * on the UI thread.
     */
    suspend fun export(passphrase: CharArray): ExportResult {
        if (passphrase.size < MIN_PASSPHRASE_LENGTH) {
            passphrase.fill(' ')
            return ExportResult.WeakPassword
        }

        // Collect all secrets on the calling thread.
        val payload = collect()

        // Encrypt on the IO dispatcher (Argon2id is intentionally slow/memory-hard).
        return withContext(ioDispatcher) {
            try {
                val passwordBytes = passphraseToBytes(passphrase)
                try {
                    val json = ExportPayload.toJson(payload)
                    val plaintext = json.toByteArray(Charsets.UTF_8)

                    // Derive key: Argon2id(password, randomSalt) → 32-byte key.
                    val salt = native.randomBytes(SALT_BYTES)
                    val key =
                        native.argon2id(
                            passphrase = passwordBytes,
                            salt = salt,
                            outLen = ChatKey.KEY_BYTES,
                            opsLimit = ARGON2ID_OPS_INTERACTIVE,
                            memLimitBytes = ARGON2ID_MEM_INTERACTIVE,
                        )

                    try {
                        // Encrypt: XChaCha20-Poly1305 with MAGIC as AAD.
                        val nonce = native.randomBytes(NONCE_BYTES)
                        val ciphertext = native.aeadEncrypt(plaintext, MAGIC, nonce, key)

                        // Assemble: MAGIC ‖ SALT ‖ NONCE ‖ CIPHERTEXT.
                        val blob = ByteArray(MAGIC.size + salt.size + nonce.size + ciphertext.size)
                        var offset = 0
                        MAGIC.copyInto(blob, offset)
                        offset += MAGIC.size
                        salt.copyInto(blob, offset)
                        offset += salt.size
                        nonce.copyInto(blob, offset)
                        offset += nonce.size
                        ciphertext.copyInto(blob, offset)

                        ExportResult.Ready(Base64.getEncoder().encodeToString(blob))
                    } finally {
                        key.fill(0)
                    }
                } finally {
                    passwordBytes.fill(0)
                }
            } finally {
                passphrase.fill(' ')
            }
        }
    }

    private fun collect(): ExportPayload {
        val connectionConfig = connectionConfigStore.load()
        val communityKey = communityKeyStore.load()
        val chatIds = chatKeyStore.listChatIds()
        val chatKeys = mutableMapOf<String, ChatKey>()
        for (chatId in chatIds) {
            chatKeyStore.load(chatId)?.let { chatKeys[chatId] = it }
        }
        val identity =
            try {
                when (val result = identityStore.load()) {
                    is IdentityLoadResult.Loaded -> result.identity
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        return ExportPayload.build(
            connectionConfig = connectionConfig,
            communityKey = communityKey,
            chatKeys = chatKeys,
            identity = identity,
        )
    }

    private fun passphraseToBytes(passphrase: CharArray): ByteArray {
        // Encode to UTF-8 bytes via a CharBuffer to avoid an intermediate immutable String.
        val charBuffer = java.nio.CharBuffer.wrap(passphrase)
        val encoder = java.nio.charset.StandardCharsets.UTF_8.newEncoder()
        val byteBuffer =
            java.nio.ByteBuffer.allocate(
                (passphrase.size * encoder.maxBytesPerChar().toInt()).coerceAtLeast(1),
            )
        try {
            encoder.encode(charBuffer, byteBuffer, true)
            encoder.flush(byteBuffer)
            byteBuffer.flip()
            val out = ByteArray(byteBuffer.remaining())
            byteBuffer.get(out)
            return out
        } finally {
            byteBuffer.array().fill(0)
            charBuffer.array().fill('\u0000')
        }
    }

    companion object {
        /** Minimum passphrase length — rejects empty/trivial passwords at the UX layer. */
        const val MIN_PASSPHRASE_LENGTH = 8

        /**
         * 16-byte magic header — identifies the export format. `owdm-export/v1\0\0`.
         * Only 14 printable chars + 2 zero-pad bytes for 16-byte alignment with the crypto blocks.
         */
        val MAGIC: ByteArray = "owdm-export/v1\u0000\u0000".toByteArray(Charsets.UTF_8)

        /** Argon2id salt length (libsodium `crypto_pwhash_SALTBYTES` = 16). */
        const val SALT_BYTES = 16

        /** XChaCha20 nonce length (libsodium `crypto_aead_xchacha20poly1305_ietf_NPUBBYTES` = 24). */
        const val NONCE_BYTES = 24

        /** Argon2id INTERACTIVE preset — same as [KeySources] in the crypto substrate. */
        const val ARGON2ID_OPS_INTERACTIVE: Long = 2L
        const val ARGON2ID_MEM_INTERACTIVE: Int = 64 * 1024 * 1024
    }
}
