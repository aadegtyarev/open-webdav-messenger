package org.openwebdav.messenger.app

import org.openwebdav.messenger.export.AccountBackup
import org.openwebdav.messenger.export.ChatBackup
import org.openwebdav.messenger.export.CommunityBackup
import org.openwebdav.messenger.keystore.StoredConnection
import org.openwebdav.messenger.transport.ConnectionConfig

internal fun appContainerRestoreBackup(
    communityId: String,
    chatId: String,
    config: ConnectionConfig,
) = AccountBackup(
    communityId,
    listOf(
        CommunityBackup(communityId, "Private group", chatId, config, listOf(ChatBackup(chatId, "Private group", "group", "private"))),
    ),
)

internal fun appContainerRestoreStored(community: CommunityBackup) =
    StoredConnection(community.config, community.anchorChatId, community.name)
