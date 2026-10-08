package org.openwebdav.messenger.app

import org.openwebdav.messenger.keystore.ChatRegistry

/** Restricts legacy public migration to the exact durable General anchor triple. */
internal object LegacyGeneralAnchorAccess {
    fun migrate(
        communityAnchorId: String?,
        storedAnchorId: String?,
        chatId: String,
        rows: List<ChatRegistry.Entry>,
    ): List<ChatRegistry.Entry>? {
        if (communityAnchorId != chatId || storedAnchorId != chatId) return null
        val anchor = rows.singleOrNull { it.id == chatId } ?: return null
        if (anchor.kind != "general") return null
        if (anchor.access == "public") return rows
        if (anchor.access != "unknown") return null
        return rows.map { if (it.id == chatId) it.copy(access = "public") else it }
    }
}
