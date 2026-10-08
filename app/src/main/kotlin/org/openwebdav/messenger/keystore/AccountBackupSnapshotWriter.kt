package org.openwebdav.messenger.keystore

import org.openwebdav.messenger.export.AccountBackup
import org.openwebdav.messenger.ui.settings.UserSettings

/** Applies account-backup snapshots to the existing stores. */
internal class AccountBackupSnapshotWriter(
    private val configStore: ConnectionConfigStore,
    private val communityRegistry: CommunityRegistry,
    private val chatRegistry: ChatRegistry,
    private val activeStore: ActiveCommunityStore,
) {
    fun clear(backup: AccountBackup) {
        val ids =
            (communityRegistry.all().map { it.id } + backup.communities.map { it.id } + ConnectionConfigStore.DEFAULT_COMMUNITY_ID)
                .distinct()
        ids.forEach(configStore::clear)
        ids.forEach(chatRegistry::clear)
        ids.forEach(UserSettings::clearCommunitySettings)
        communityRegistry.replace(emptyList())
        activeStore.clear()
        UserSettings.selectCommunity("default")
    }

    fun write(backup: AccountBackup) {
        val previousIds = (communityRegistry.all().map { it.id } + ConnectionConfigStore.DEFAULT_COMMUNITY_ID).distinct()
        (previousIds + backup.communities.map { it.id }).distinct().forEach(UserSettings::clearCommunitySettings)
        previousIds.forEach(configStore::clear)
        (previousIds - backup.communities.map { it.id }.toSet()).forEach(chatRegistry::clear)
        backup.communities.forEach { community ->
            configStore.save(community.config, community.anchorChatId, community.name, community.id)
            chatRegistry.replace(community.id, community.chats.map { ChatRegistry.Entry(it.id, it.name, it.kind) })
        }
        communityRegistry.replace(backup.communities.map { CommunityRegistry.Entry(it.id, it.name, it.anchorChatId) })
        activeStore.select(backup.activeCommunityId)
        UserSettings.selectCommunity(backup.activeCommunityId)
        backup.communities.forEach { community ->
            UserSettings.setHostFor(community.id, community.isHost)
            UserSettings.setCommunityMetadata(community.id, community.pollFloorSeconds, community.retentionWindowDays)
        }
    }
}
