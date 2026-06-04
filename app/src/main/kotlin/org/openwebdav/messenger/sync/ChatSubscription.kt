package org.openwebdav.messenger.sync

import org.openwebdav.messenger.protocol.ChangeEntry

/**
 * A chat this member belongs to on the disk — the "local roster of joined chats" a reader uses to map
 * a §9.2 change-entry `chat-tag` back to a chat-id (`docs/protocol/webdav-layout.md` §9.3 step 1).
 *
 * The member set / which chats are joined is supplied **out-of-band / from config** in the sync
 * feature (plan → Member list source; §1.3) — sync reads it, does not manage it.
 *
 * @property chatId the chat identifier (§8 tag 0x01 value); the cursor and log are keyed by it.
 */
data class ChatSubscription(val chatId: String) {
    /** §9.2: the chat-tag a change entry uses to name this chat (`b32lower(SHA-256(chat-id))[0:16]`). */
    val chatTag: String = ChangeEntry.chatTag(chatId)
}
