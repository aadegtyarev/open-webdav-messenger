package org.openwebdav.messenger.keystore

import org.openwebdav.messenger.export.AccountBackup
import org.openwebdav.messenger.ui.settings.UserSettings

/** Applies account-backup snapshots to the existing stores. */
internal class AccountBackupSnapshotWriter(
    private val configStore: AccountBackupConfigStore,
    private val communityRegistry: CommunityRegistry,
    private val chatRegistry: ChatRegistry,
    private val activeStore: ActiveCommunityStore,
) {
    fun clear(extraIds: Set<String>) {
        val ids = (communityRegistry.all().map { it.id } + extraIds + ConnectionConfigStore.DEFAULT_COMMUNITY_ID).distinct()
        ids.forEach(configStore::clear)
        ids.forEach(chatRegistry::clear)
        ids.forEach(UserSettings::clearCommunitySettingsStrict)
        communityRegistry.replace(emptyList())
        activeStore.clear()
        UserSettings.selectCommunity("default")
    }

    fun write(backup: AccountBackup) {
        val previousIds = (communityRegistry.all().map { it.id } + ConnectionConfigStore.DEFAULT_COMMUNITY_ID).toSet()
        val replacementIds = backup.communities.mapTo(mutableSetOf()) { it.id }
        (previousIds + replacementIds).forEach(UserSettings::clearCommunitySettingsStrict)
        (previousIds - replacementIds).forEach(configStore::clear)
        (previousIds - replacementIds).forEach(chatRegistry::clear)
        backup.communities.forEach { community ->
            configStore.save(community.config, community.anchorChatId, community.name, community.id)
            chatRegistry.replace(community.id, community.chats.map { ChatRegistry.Entry(it.id, it.name, it.kind, it.access) })
        }
        communityRegistry.replace(backup.communities.map { CommunityRegistry.Entry(it.id, it.name, it.anchorChatId) })
        activeStore.selectStrict(backup.activeCommunityId)
        UserSettings.selectCommunity(backup.activeCommunityId)
        backup.communities.forEach { community ->
            UserSettings.setHostForStrict(community.id, community.isHost)
            UserSettings.setCommunityMetadataStrict(community.id, community.pollFloorSeconds, community.retentionWindowDays)
        }
    }
}
