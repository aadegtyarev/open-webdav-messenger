package org.openwebdav.messenger.export

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openwebdav.messenger.account.AccountMutationBarrier
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.NativeCrypto
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.identity.IdentityLoadResult
import org.openwebdav.messenger.keystore.AccountIdentifier
import org.openwebdav.messenger.transport.PathSafety
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
    private val activateRuntime: () -> Unit = {},
    private val restorePreviousRuntime: () -> Unit = activateRuntime,
    private val invalidateLocalCaches: () -> Unit = {},
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

        if (blob.length > ExportManager.MAX_BLOB_BASE64_CHARS) {
            passphrase.fill(' ')
            return RestoreResult.BadFormat
        }
        val blobBytes: ByteArray =
            try {
                Base64.getDecoder().decode(blob)
            } catch (_: IllegalArgumentException) {
                passphrase.fill(' ')
                return RestoreResult.BadFormat
            }

        if (blobBytes.size > ExportManager.MAX_BLOB_BYTES) {
            passphrase.fill(' ')
            blobBytes.fill(0)
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
                        try {
                            if (plaintext.size > ExportPayload.MAX_PLAINTEXT_BYTES) return@withContext RestoreResult.CorruptPayload
                            val json = String(plaintext, Charsets.UTF_8)
                            val payload =
                                ExportPayload.fromJson(json)
                                    ?: return@withContext RestoreResult.CorruptPayload
                            val staged = stage(payload) ?: return@withContext RestoreResult.CorruptPayload
                            return@withContext AccountMutationBarrier.process.withExclusive {
                                AccountMutationBarrier.process.withAccountReplacement {
                                    writeWithRollback(staged)
                                }
                            }
                        } finally {
                            plaintext.fill(0)
                        }
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
        val chatKeys: Map<String, ChatKey>,
        val identity: Identity,
        val accountBackup: AccountBackup,
        val accountCommunityKeys: Map<String, ChatKey>,
        val legacy: Boolean,
    )

    private fun stage(payload: ExportPayload): StagedRestore? {
        return try {
            val chatKeys = payload.chatKeys.mapValues { (_, encoded) -> decodeKey(encoded) ?: return null }
            val serializedIdentity = payload.identitySerialized?.let(ExportPayload::decodeBase64) ?: return null
            val identity =
                try {
                    Identity.deserialize(serializedIdentity) ?: return null
                } finally {
                    serializedIdentity.fill(0)
                }
            val legacy = payload.accountBackupBase64 == null
            val communityKey = payload.communityKeyBase64?.let { decodeKey(it) ?: return null }
            if (accountBackupStore == null || (!legacy && communityKey != null)) return null
            val backup =
                payload.accountBackupBase64?.let { encoded ->
                    val raw = ExportPayload.decodeBase64(encoded)
                    try {
                        AccountBackupCodec.decode(raw) ?: return null
                    } finally {
                        raw.fill(0)
                    }
                } ?: legacyBackup(payload.connectionConfig, communityKey, chatKeys) ?: return null
            if (!validateBackup(backup, chatKeys)) return null
            val accountCommunityKeys = mutableMapOf<String, ChatKey>()
            backup.communities.forEach { community ->
                community.communityKeyBase64?.let { encoded ->
                    accountCommunityKeys[community.id] = decodeKey(encoded) ?: return null
                }
            }
            if (legacy) accountCommunityKeys["default"] = checkNotNull(communityKey)
            StagedRestore(chatKeys, identity, backup, accountCommunityKeys, legacy)
        } catch (_: Exception) {
            null
        }
    }

    private fun legacyBackup(
        config: org.openwebdav.messenger.transport.ConnectionConfig?,
        communityKey: ChatKey?,
        chatKeys: Map<String, ChatKey>,
    ): AccountBackup? {
        if (accountBackupStore == null || config == null || communityKey == null || chatKeys.size != 1) return null
        val chatId = chatKeys.keys.single()
        val rawKey = communityKey.export()
        val encodedKey =
            try {
                java.util.Base64.getEncoder().encodeToString(rawKey)
            } finally {
                rawKey.fill(0)
            }
        return AccountBackup(
            "default",
            listOf(
                CommunityBackup(
                    "default",
                    "Restored community",
                    chatId,
                    config,
                    listOf(ChatBackup(chatId, "General", "general")),
                    encodedKey,
                ),
            ),
        )
    }

    private fun validateBackup(
        backup: AccountBackup,
        chatKeys: Map<String, ChatKey>,
    ): Boolean =
        chatKeys.keys.all(AccountIdentifier::isValid) &&
            backup.communities.map { it.id }.distinct().size == backup.communities.size &&
            backup.communities.any { it.id == backup.activeCommunityId } &&
            backup.communities.all { community ->
                AccountIdentifier.isValid(community.id) && community.name.isNotBlank() &&
                    PathSafety.isValidConnectionConfig(community.config) &&
                    AccountIdentifier.isValid(community.anchorChatId) &&
                    community.anchorChatId in community.chats.map { it.id } &&
                    community.chats.isNotEmpty() && community.chats.map { it.id }.distinct().size == community.chats.size &&
                    community.chats.all {
                        AccountIdentifier.isValid(it.id) && it.name.isNotBlank() && it.kind in setOf("general", "group", "dm") &&
                            it.id in chatKeys
                    }
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
        val membershipState: Boolean,
        val registryState: Boolean,
    ) {
        fun isEmpty(): Boolean =
            config == null && communityKeys.isEmpty() && chatKeys.isEmpty() &&
                identity is IdentityLoadResult.None && accountBackup == null && !membershipState && !registryState
    }

    private suspend fun writeWithRollback(staged: StagedRestore): RestoreResult {
        try {
            invalidateLocalCaches()
        } catch (_: Exception) {
            return RestoreResult.StoreFailure(rollbackSucceeded = true)
        }
        val previous =
            try {
                val configPresent = connectionConfigStore.hasStored()
                val config = connectionConfigStore.load()
                check(!configPresent || config != null) { "Stored connection config is unreadable" }
                val communityKeys =
                    communityKeyStore.listCommunityIds().associateWith { id ->
                        checkNotNull(communityKeyStore.load(id)) { "Stored community key is unreadable: $id" }
                    }
                val chatKeys =
                    chatKeyStore.listChatIds().associateWith { id ->
                        checkNotNull(chatKeyStore.load(id)) { "Stored chat key is unreadable: $id" }
                    }
                RestoreSnapshot(
                    config,
                    communityKeys,
                    chatKeys,
                    identityStore.load(),
                    accountBackupStore?.snapshot(),
                    accountBackupStore?.hasMembershipState() ?: false,
                    accountBackupStore?.hasRegistryState() ?: false,
                )
            } catch (_: Exception) {
                return RestoreResult.StoreFailure(rollbackSucceeded = true)
            }
        if (previous.identity is IdentityLoadResult.Unrecoverable) return RestoreResult.StoreFailure(rollbackSucceeded = true)
        if (previous.registryState && previous.accountBackup == null) return RestoreResult.StoreFailure(rollbackSucceeded = true)
        if (staged.legacy && !previous.isEmpty()) return RestoreResult.IncompatibleTarget

        var activationAttempted = false
        return try {
            communityKeyStore.replaceAllStrict(staged.accountCommunityKeys)
            chatKeyStore.replaceAllStrict(staged.chatKeys)
            identityStore.store(staged.identity)
            checkNotNull(accountBackupStore).replace(staged.accountBackup)
            activationAttempted = true
            activateRuntime()
            RestoreResult.Restored
        } catch (_: Exception) {
            var rolledBack = true

            fun attempt(action: () -> Unit) {
                try {
                    action()
                } catch (_: Exception) {
                    rolledBack = false
                }
            }
            attempt {
                if (previous.accountBackup != null) {
                    checkNotNull(accountBackupStore).replace(previous.accountBackup)
                } else {
                    checkNotNull(accountBackupStore).clearCommunityIds(staged.accountBackup.communities.mapTo(mutableSetOf()) { it.id })
                }
            }
            attempt {
                if (previous.accountBackup == null) {
                    if (previous.config == null) connectionConfigStore.clear() else connectionConfigStore.store(previous.config)
                }
            }
            attempt { communityKeyStore.replaceAllStrict(previous.communityKeys) }
            previous.chatKeys.forEach { (id, key) -> attempt { chatKeyStore.storeStrict(id, key) } }
            (staged.chatKeys.keys - previous.chatKeys.keys).forEach { id -> attempt { chatKeyStore.removeStrict(id) } }
            attempt { chatKeyStore.replaceAllStrict(previous.chatKeys) }
            attempt {
                when (val oldIdentity = previous.identity) {
                    is IdentityLoadResult.Loaded -> identityStore.store(oldIdentity.identity)
                    IdentityLoadResult.None -> identityStore.clear()
                    is IdentityLoadResult.Unrecoverable -> error("Previous identity was unrecoverable")
                }
            }
            if (activationAttempted) attempt(restorePreviousRuntime)
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
