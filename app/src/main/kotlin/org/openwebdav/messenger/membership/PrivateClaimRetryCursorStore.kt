package org.openwebdav.messenger.membership

import android.content.Context
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.keystore.AccountIdentifier
import org.openwebdav.messenger.keystore.StrictFileOperations
import org.openwebdav.messenger.protocol.HashTag
import java.io.File
import java.nio.file.Files

/** No-backup cursor keeps bounded background retries fair across process restarts. */
internal class PrivateClaimRetryCursorStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "private_membership/retry_cursor")

    fun load(
        communityId: String,
        identity: Identity,
    ): String? = runCatching { file(communityId, identity).takeIf { it.isFile }?.readText()?.takeIf(String::isNotBlank) }.getOrNull()

    fun save(
        communityId: String,
        identity: Identity,
        chatId: String,
    ) {
        AccountIdentifier.requireValid(chatId)
        val target = file(communityId, identity)
        val parent = target.parentFile ?: error("retry cursor directory is unavailable")
        parent.mkdirs()
        val temp = Files.createTempFile(parent.toPath(), ".cursor-", ".tmp").toFile()
        try {
            temp.writeText(chatId)
            StrictFileOperations.atomicReplace(temp, target)
        } finally {
            if (temp.exists()) runCatching { StrictFileOperations.delete(temp) }
        }
    }

    fun clearAll() {
        directory.listFiles()?.forEach(StrictFileOperations::delete)
    }

    private fun file(
        communityId: String,
        identity: Identity,
    ): File {
        AccountIdentifier.requireValid(communityId)
        val name = HashTag.tag("$communityId\u001f".toByteArray() + identity.copySignPublic(), 32)
        return File(directory, "$name.cursor")
    }
}
