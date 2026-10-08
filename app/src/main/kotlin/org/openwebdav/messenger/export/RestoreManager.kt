package org.openwebdav.messenger.export

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.NativeCrypto
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.identity.IdentityLoadResult
import java.util.Base64

/**
 * Accepts a base64 export blob + passphrase, validates and decrypts it, then populates all
 * device-local secret stores (connection config, community key, chat keys, identity).
 *
 * Every validation failure (wrong password, tampered blob, wrong version, corrupt inner payload)
 * is a typed [RestoreResult] — never a partial restore, never an uncaught exception. Either
 * ALL stores are populated, or NONE are.
 *
 * The passphrase [CharArray] is consumed and zeroized.
 */
class RestoreManager(
    private val native: NativeCrypto,
    private val connectionConfigStore: ExportableConnectionConfigStore,
    private val communityKeyStore: ExportableCommunityKeyStore,
    private val chatKeyStore: ExportableChatKeyStore,
    private val identityStore: ExportableIdentityStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Decrypt [blob] (base64-encoded export) with [passphrase] and populate all stores.
     * The stores are written ONLY after the full payload is validated (atomic: all-or-nothing).
     * The passphrase char array is consumed (zeroized) before this returns.
     */
    suspend fun restore(
        blob: String,
        passphrase: CharArray,
    ): RestoreResult {
        if (passphrase.size < ExportManager.MIN_PASSPHRASE_LENGTH) {
            passphrase.fill(' ')
            return RestoreResult.WeakPassword
        }

        val blobBytes: ByteArray =
            try {
                Base64.getDecoder().decode(blob)
            } catch (_: IllegalArgumentException) {
                passphrase.fill(' ')
                return RestoreResult.BadFormat
            }

        return withContext(ioDispatcher) {
            try {
                val passwordBytes = passphraseToBytes(passphrase)
                try {
                    // Validate magic header.
                    val magic = ExportManager.MAGIC
                    if (blobBytes.size < magic.size + ExportManager.SALT_BYTES + ExportManager.NONCE_BYTES + AEAD_OVERHEAD) {
                        return@withContext RestoreResult.BadFormat
                    }
                    val headerMagic = blobBytes.copyOfRange(0, magic.size)
                    if (!headerMagic.contentEquals(magic)) {
                        return@withContext RestoreResult.BadFormat
                    }

                    var offset = magic.size
                    val salt = blobBytes.copyOfRange(offset, offset + ExportManager.SALT_BYTES)
                    offset += ExportManager.SALT_BYTES
                    val nonce = blobBytes.copyOfRange(offset, offset + ExportManager.NONCE_BYTES)
                    offset += ExportManager.NONCE_BYTES
                    val ciphertext = blobBytes.copyOfRange(offset, blobBytes.size)

                    // Derive key: Argon2id(password, salt) → 32-byte key.
                    val key =
                        native.argon2id(
                            passphrase = passwordBytes,
                            salt = salt,
                            outLen = ChatKey.KEY_BYTES,
                            opsLimit = ExportManager.ARGON2ID_OPS_INTERACTIVE,
                            memLimitBytes = ExportManager.ARGON2ID_MEM_INTERACTIVE,
                        )

                    try {
                        // Decrypt: XChaCha20-Poly1305 with MAGIC as AAD.
                        val plaintext =
                            native.aeadDecrypt(ciphertext, magic, nonce, key)
                                ?: return@withContext RestoreResult.WrongPasswordOrTampered

                        // Parse the inner JSON payload.
                        val json = String(plaintext, Charsets.UTF_8)
                        val payload =
                            ExportPayload.fromJson(json)
                                ?: return@withContext RestoreResult.CorruptPayload

                        // Validate all binary and identity data before touching persistent stores.
                        val staged = stage(payload) ?: return@withContext RestoreResult.CorruptPayload
                        return@withContext writeWithRollback(staged)
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

    private data class StagedRestore(
        val payload: ExportPayload,
        val communityKey: ChatKey?,
        val chatKeys: Map<String, ChatKey>,
        val identity: Identity,
    )

    private fun stage(payload: ExportPayload): StagedRestore? =
        try {
            val communityKey = payload.communityKeyBase64?.let { decodeKey(it) ?: return null }
            val chatKeys = payload.chatKeys.mapValues { (_, encoded) -> decodeKey(encoded) ?: return null }
            val serializedIdentity = payload.identitySerialized?.let(ExportPayload::decodeBase64) ?: return null
            val identity =
                try {
                    Identity.deserialize(serializedIdentity) ?: return null
                } finally {
                    serializedIdentity.fill(0)
                }
            payload.connectionConfig?.let { config ->
                if (!config.baseUrl.startsWith("https://") || config.username.isBlank() ||
                    config.appPassword.isBlank() || config.chatRoot.isBlank()
                ) {
                    return null
                }
            }
            StagedRestore(payload, communityKey, chatKeys, identity)
        } catch (_: Exception) {
            null
        }

    private fun decodeKey(encoded: String): ChatKey? {
        val raw = ExportPayload.decodeBase64(encoded)
        return try {
            if (raw.size != ChatKey.KEY_BYTES) null else ChatKey.fromBytes(raw)
        } finally {
            raw.fill(0)
        }
    }

    private fun writeWithRollback(staged: StagedRestore): RestoreResult {
        val previousConfig = connectionConfigStore.load()
        val previousCommunityKey = communityKeyStore.load()
        val previousChatKeys = chatKeyStore.listChatIds().mapNotNull { id -> chatKeyStore.load(id)?.let { id to it } }.toMap()
        val previousIdentity = identityStore.load()
        if (previousIdentity is IdentityLoadResult.Unrecoverable) return RestoreResult.StoreFailure(rollbackSucceeded = true)
        return try {
            staged.payload.connectionConfig?.let(connectionConfigStore::store)
            staged.communityKey?.let(communityKeyStore::store)
            staged.chatKeys.forEach { (id, key) -> chatKeyStore.store(id, key) }
            identityStore.store(staged.identity)
            RestoreResult.Restored
        } catch (_: Exception) {
            val rolledBack =
                runCatching {
                    connectionConfigStore.clear()
                    previousConfig?.let(connectionConfigStore::store)
                    communityKeyStore.clear()
                    previousCommunityKey?.let(communityKeyStore::store)
                    (chatKeyStore.listChatIds() - previousChatKeys.keys).forEach(chatKeyStore::remove)
                    previousChatKeys.forEach(chatKeyStore::store)
                    identityStore.clear()
                    (previousIdentity as? IdentityLoadResult.Loaded)?.identity?.let(identityStore::store)
                }.isSuccess
            RestoreResult.StoreFailure(rollbackSucceeded = rolledBack)
        }
    }

    private fun passphraseToBytes(passphrase: CharArray): ByteArray {
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
        /** AEAD overhead: nonce(24) + tag(16) = 40 — minimum ciphertext size for a valid blob. */
        private val AEAD_OVERHEAD = ExportManager.NONCE_BYTES + 16 // Poly1305 tag
    }
}
