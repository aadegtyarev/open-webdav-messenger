package org.openwebdav.messenger.membership

import org.openwebdav.messenger.protocol.HashTag

/** Chat-root-relative collection and content-addressed filenames for one capability chat. */
internal object PrivateMembershipPaths {
    private val chatIdPattern = Regex("[A-Za-z0-9_-]{1,${PrivateMembershipFormat.MAX_CHAT_ID_BYTES}}")

    fun collection(chatId: String): String {
        require(chatIdPattern.matches(chatId)) { "invalid private chat id" }
        return "${PrivateMembershipFormat.COLLECTION}/$chatId"
    }

    fun entryName(fileBytes: ByteArray): String = HashTag.tag(fileBytes, PrivateMembershipFormat.ENTRY_NAME_BYTES)

    fun entryPath(
        chatId: String,
        name: String,
    ): String {
        require(isWellFormedEntryName(name)) { "invalid private membership entry name" }
        return "${collection(chatId)}/$name"
    }

    fun isWellFormedEntryName(name: String): Boolean =
        name.length == PrivateMembershipFormat.ENTRY_NAME_BYTES && name.all { it in HashTag.BASE32_LOWER_CHARS }
}
