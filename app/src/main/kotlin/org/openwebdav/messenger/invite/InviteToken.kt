package org.openwebdav.messenger.invite

/**
 * The self-contained invite a community owner hands to a joining member (`ui-chat-surface` plan →
 * Contracts: "An invite token codec"). It bundles everything a member needs to silently configure the
 * app and land in the community chat, so the member types nothing and never sees the disk credentials.
 *
 * **Bearer token, plain encoding — NOT encryption** (plan key design decision; threat model bearer-invite
 * row): whoever holds the token is in. The codec ([InviteCodec]) frames it as `owdm1:<base64url(gzip(json))>`
 * for out-of-band transit (copied / scanned). It is **never** written to the WebDAV disk and **never**
 * logged — [toString] is redacted, like `ConnectionConfig`/`ChatKey`, because it carries the disk
 * [appPassword] and the raw [chatKey] in the clear.
 *
 * @property baseUrl WebDAV endpoint (must be HTTPS — the owner-create flow refuses cleartext before minting).
 * @property username WebDAV / cloud login.
 * @property appPassword the disk app-password (secret — redacted in [toString], never logged).
 * @property chatRoot the chat-root folder the credential is scoped to.
 * @property chatId the community chat's identifier (the one mandatory chat — a community IS its chat).
 * @property chatKey the raw 32-byte random chat key (secret — redacted, never logged). Carried so a
 *   member needs no passphrase (decision 9 `random` source, distributed out-of-band by this feature).
 * @property communityName the human-facing community name = the community chat's title.
 */
internal data class InviteToken(
    val baseUrl: String,
    val username: String,
    val appPassword: String,
    val chatRoot: String,
    val chatId: String,
    val chatKey: ByteArray,
    val communityName: String,
) {
    init {
        require(chatKey.size == CHAT_KEY_BYTES) { "chatKey must be $CHAT_KEY_BYTES bytes, got ${chatKey.size}" }
    }

    /** Redacted — an invite carries the app-password + raw chat key and must never print them. */
    override fun toString(): String =
        "InviteToken(baseUrl=$baseUrl, username=$username, appPassword=***, " +
            "chatRoot=$chatRoot, chatId=$chatId, chatKey=***, communityName=$communityName)"

    // equals/hashCode are content-based over every field (the chatKey by content) so a decode round-trip
    // can be asserted byte-identically in tests; the array field is the reason these are explicit.
    override fun equals(other: Any?): Boolean =
        other is InviteToken &&
            baseUrl == other.baseUrl &&
            username == other.username &&
            appPassword == other.appPassword &&
            chatRoot == other.chatRoot &&
            chatId == other.chatId &&
            chatKey.contentEquals(other.chatKey) &&
            communityName == other.communityName

    override fun hashCode(): Int {
        var result = baseUrl.hashCode()
        result = 31 * result + username.hashCode()
        result = 31 * result + appPassword.hashCode()
        result = 31 * result + chatRoot.hashCode()
        result = 31 * result + chatId.hashCode()
        result = 31 * result + chatKey.contentHashCode()
        result = 31 * result + communityName.hashCode()
        return result
    }

    companion object {
        /** The raw chat-key width — single-sourced semantics with `crypto.ChatKey.KEY_BYTES` (32). */
        const val CHAT_KEY_BYTES = 32
    }
}
