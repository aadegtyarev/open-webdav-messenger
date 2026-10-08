package org.openwebdav.messenger.app

import org.openwebdav.messenger.directory.DirectoryReadResult
import org.openwebdav.messenger.export.ExportTestSupport
import org.openwebdav.messenger.keystore.StoredConnection

internal fun configureRestoreOpenSeam(
    communityId: String,
    account: ExportTestSupport.InMemoryAccountBackupStore,
    chatKeys: ExportTestSupport.InMemoryChatKeyStore,
) {
    AppContainer.configureChatOpenTestSeam(
        communityId,
        AppContainer.ChatOpenTestSeam(
            loadChatKey = { chatKeys.load(it) },
            loadStored = { id ->
                account.snapshot()?.communities?.singleOrNull { it.id == id }?.let {
                    StoredConnection(it.config, it.anchorChatId, it.name)
                }
            },
            readDirectory = { DirectoryReadResult(emptyList(), 0) },
            chatKind = { _, _ -> "group" },
            chatAccess = { _, _ -> "private" },
        ),
    )
}
