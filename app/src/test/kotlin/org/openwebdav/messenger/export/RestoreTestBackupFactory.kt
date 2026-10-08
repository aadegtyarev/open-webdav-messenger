package org.openwebdav.messenger.export

internal fun restoreTestBackup(
    community: String,
    anchor: String,
): AccountBackup =
    AccountBackup(
        community,
        listOf(
            CommunityBackup(
                community,
                community,
                anchor,
                ExportTestSupport.sampleConfig(),
                listOf(ChatBackup(anchor, "General", "general")),
            ),
        ),
    )
