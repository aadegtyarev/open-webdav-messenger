package org.openwebdav.messenger.governance

import org.openwebdav.messenger.chatdirectory.ChatAccess

data class CommunityGroupOrigin(
    val communityId: String,
    val chatId: String,
    val access: ChatAccess,
    val creatorSigningPublicKey: ByteArray,
    val creatorBoxPublicKey: ByteArray,
    val nonce: ByteArray,
)
