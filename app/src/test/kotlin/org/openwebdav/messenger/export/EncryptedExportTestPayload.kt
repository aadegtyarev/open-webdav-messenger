package org.openwebdav.messenger.export

import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.NativeCrypto
import java.util.Base64

internal fun encryptedExportTestPayload(
    native: NativeCrypto,
    json: String,
    password: String,
): String {
    val salt = native.randomBytes(ExportManager.SALT_BYTES)
    val passwordBytes = password.toByteArray(Charsets.UTF_8)
    val key =
        native.argon2id(
            passwordBytes,
            salt,
            ChatKey.KEY_BYTES,
            ExportManager.ARGON2ID_OPS_INTERACTIVE,
            ExportManager.ARGON2ID_MEM_INTERACTIVE,
        )
    val nonce = native.randomBytes(ExportManager.NONCE_BYTES)
    return try {
        Base64.getEncoder().encodeToString(
            ExportManager.MAGIC + salt + nonce + native.aeadEncrypt(json.toByteArray(Charsets.UTF_8), ExportManager.MAGIC, nonce, key),
        )
    } finally {
        passwordBytes.fill(0)
        key.fill(0)
    }
}
