package org.openwebdav.messenger.membership

import android.content.Context
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.keystore.AccountIdentifier
import org.openwebdav.messenger.keystore.StrictFileOperations
import org.openwebdav.messenger.protocol.HashTag
import java.io.File
import java.nio.file.Files

internal data class PrivateClaimRetryCursors(val pendingChatId: String? = null, val freshChatId: String? = null)

/** Versioned cursor format migrates the legacy shared cursor into both independent queues. */
internal object PrivateClaimRetryCursorCodec {
    private const val PREFIX = "v2\n"

    fun encode(cursors: PrivateClaimRetryCursors): String {
        require(cursors.pendingChatId == null || AccountIdentifier.isValid(cursors.pendingChatId))
        require(cursors.freshChatId == null || AccountIdentifier.isValid(cursors.freshChatId))
        return PREFIX + (cursors.pendingChatId ?: "") + "\n" + (cursors.freshChatId ?: "")
    }

    fun decode(value: String): PrivateClaimRetryCursors? =
        runCatching {
            if (value.startsWith(PREFIX)) {
                require(value.count { it == '\n' } == 2)
                val fields = value.removePrefix(PREFIX).split('\n')
                require(fields.size == 2)
                PrivateClaimRetryCursors(cursor(fields[0]), cursor(fields[1]))
            } else {
                require(AccountIdentifier.isValid(value))
                PrivateClaimRetryCursors(value, value)
            }
        }.getOrNull()

    private fun cursor(value: String): String? =
        if (value.isEmpty()) null else value.takeIf(AccountIdentifier::isValid) ?: error("invalid cursor")
}

/** No-backup cursors keep bounded pending and fresh retries fair across process restarts. */
internal class PrivateClaimRetryCursorStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "private_membership/retry_cursor")

    @Synchronized
    fun load(
        communityId: String,
        identity: Identity,
    ): PrivateClaimRetryCursors =
        runCatching { file(communityId, identity).takeIf { it.isFile }?.readText()?.let(PrivateClaimRetryCursorCodec::decode) }
            .getOrNull() ?: PrivateClaimRetryCursors()

    fun savePending(
        communityId: String,
        identity: Identity,
        chatId: String,
    ) = update(communityId, identity) { it.copy(pendingChatId = chatId) }

    fun saveFresh(
        communityId: String,
        identity: Identity,
        chatId: String,
    ) = update(communityId, identity) { it.copy(freshChatId = chatId) }

    @Synchronized
    private fun update(
        communityId: String,
        identity: Identity,
        change: (PrivateClaimRetryCursors) -> PrivateClaimRetryCursors,
    ) {
        val current = load(communityId, identity)
        val next = change(current)
        val target = file(communityId, identity)
        val parent = target.parentFile ?: error("retry cursor directory is unavailable")
        parent.mkdirs()
        val temp = Files.createTempFile(parent.toPath(), ".cursor-", ".tmp").toFile()
        try {
            temp.writeText(PrivateClaimRetryCursorCodec.encode(next))
            StrictFileOperations.atomicReplace(temp, target)
        } finally {
            if (temp.exists()) runCatching { StrictFileOperations.delete(temp) }
        }
    }

    @Synchronized
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
