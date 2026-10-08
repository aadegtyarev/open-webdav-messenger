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
    private val accountBackupStore: ExportableAccountBackupStore? = null,
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
        val accountBackup: AccountBackup?,
        val accountCommunityKeys: Map<String, ChatKey>,
    )

    private fun stage(payload: ExportPayload): StagedRestore? {
        return try {
            val communityKey = payload.communityKeyBase64?.let { decodeKey(it) ?: return null }
            val chatKeys = payload.chatKeys.mapValues { (_, encoded) -> decodeKey(encoded) ?: return null }
            val serializedIdentity = payload.identitySerialized?.let(ExportPayload::decodeBase64) ?: return null
            val identity =
                try {
                    Identity.deserialize(serializedIdentity) ?: return null
                } finally {
                    serializedIdentity.fill(0)
                }
            val accountBackup =
                payload.accountBackupBase64?.let { encoded ->
                    val raw = ExportPayload.decodeBase64(encoded)
                    try {
                        AccountBackupCodec.decode(raw) ?: return null
                    } finally {
                        raw.fill(0)
                    }
                }
            payload.connectionConfig?.let { config ->
                if (!isValidConfig(config)) return null
            }
            if (accountBackup != null && communityKey != null) return null
            val accountCommunityKeys = mutableMapOf<String, ChatKey>()
            accountBackup?.let { backup ->
                if (backup.communities.map { it.id }.distinct().size != backup.communities.size) return null
                if (backup.communities.any { community ->
                        !isSafeIdentifier(community.id) || !isValidConfig(community.config) ||
                            !isSafeIdentifier(community.anchorChatId) || community.anchorChatId !in community.chats.map { it.id } ||
                            community.chats.any {
                                !isSafeIdentifier(it.id) || it.name.isBlank() || it.kind !in setOf("general", "group", "dm") ||
                                    it.id !in chatKeys
                            } || community.chats.map { it.id }.distinct().size != community.chats.size
                    }
                ) {
                    return null
                }
                backup.communities.forEach { community ->
                    community.communityKeyBase64?.let { encoded ->
                        accountCommunityKeys[community.id] = decodeKey(encoded) ?: return null
                    }
                }
            }
            StagedRestore(payload, communityKey, chatKeys, identity, accountBackup, accountCommunityKeys)
        } catch (_: Exception) {
            null
        }
    }

    private fun isSafeIdentifier(value: String): Boolean = value.matches(Regex("[A-Za-z0-9_-]{1,128}"))

    private fun isValidConfig(config: org.openwebdav.messenger.transport.ConnectionConfig): Boolean {
        val uri = java.net.URI(config.baseUrl)
        return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
            config.username.isNotBlank() && config.appPassword.isNotBlank() && config.chatRoot.isNotBlank() &&
            !config.chatRoot.startsWith('/') && !config.chatRoot.contains('\\') &&
            config.chatRoot.split('/').none { it == "." || it == ".." || it.isEmpty() }
    }

    private fun decodeKey(encoded: String): ChatKey? {
        val raw = ExportPayload.decodeBase64(encoded)
        return try {
            if (raw.size != ChatKey.KEY_BYTES) null else ChatKey.fromBytes(raw)
        } finally {
            raw.fill(0)
        }
    }

    private data class RestoreSnapshot(
        val config: org.openwebdav.messenger.transport.ConnectionConfig?,
        val communityKeys: Map<String, ChatKey>,
        val chatKeys: Map<String, ChatKey>,
        val identity: IdentityLoadResult,
        val accountBackup: AccountBackup?,
    )

    private fun writeWithRollback(staged: StagedRestore): RestoreResult {
        val previous =
            try {
                RestoreSnapshot(
                    connectionConfigStore.load(),
                    communityKeyStore.listCommunityIds().mapNotNull { id -> communityKeyStore.load(id)?.let { id to it } }.toMap(),
                    chatKeyStore.listChatIds().mapNotNull { id -> chatKeyStore.load(id)?.let { id to it } }.toMap(),
                    identityStore.load(),
                    accountBackupStore?.snapshot(),
                )
            } catch (_: Exception) {
                return RestoreResult.StoreFailure(rollbackSucceeded = true)
            }
        if (previous.identity is IdentityLoadResult.Unrecoverable) return RestoreResult.StoreFailure(rollbackSucceeded = true)
        return try {
            staged.payload.connectionConfig?.let(connectionConfigStore::store)
            if (staged.accountBackup != null) {
                communityKeyStore.replaceAll(staged.accountCommunityKeys)
                staged.communityKey?.let(communityKeyStore::store)
                chatKeyStore.replaceAll(staged.chatKeys)
            } else {
                staged.communityKey?.let(communityKeyStore::store)
                staged.chatKeys.forEach { (id, key) -> chatKeyStore.store(id, key) }
            }
            identityStore.store(staged.identity)
            staged.accountBackup?.let { backup ->
                checkNotNull(accountBackupStore) { "This app version cannot restore account registries" }.replace(backup)
            }
            RestoreResult.Restored
        } catch (_: Exception) {
            val rolledBack =
                runCatching {
                    connectionConfigStore.clear()
                    previous.config?.let(connectionConfigStore::store)
                    communityKeyStore.replaceAll(previous.communityKeys)
                    chatKeyStore.replaceAll(previous.chatKeys)
                    identityStore.clear()
                    (previous.identity as? IdentityLoadResult.Loaded)?.identity?.let(identityStore::store)
                    if (previous.accountBackup != null) {
                        accountBackupStore?.replace(previous.accountBackup)
                    } else if (staged.accountBackup != null) {
                        accountBackupStore?.clear()
                    }
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
