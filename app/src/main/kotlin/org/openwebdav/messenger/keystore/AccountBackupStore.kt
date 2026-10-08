package org.openwebdav.messenger.keystore

import android.content.Context
import org.openwebdav.messenger.export.AccountBackup
import org.openwebdav.messenger.export.ChatBackup
import org.openwebdav.messenger.export.CommunityBackup
import org.openwebdav.messenger.export.ExportableAccountBackupStore
import org.openwebdav.messenger.ui.settings.UserSettings

/** Bridges the encrypted account backup to the existing per-community secure and registry stores. */
internal class AccountBackupStore(private val context: Context) : ExportableAccountBackupStore {
    private val configStore = ConnectionConfigStore(context)
    private val communityRegistry = CommunityRegistry(context)
    private val chatRegistry = ChatRegistry(context)
    private val activeStore = ActiveCommunityStore(context)
    private val communityKeyStore = CommunityKeyStore(context)

    override fun snapshot(): AccountBackup? {
        val entries = communityRegistry.all()
        val effectiveEntries =
            entries.ifEmpty {
                configStore.loadStored()?.takeIf { it.chatId.isNotBlank() }?.let {
                    listOf(CommunityRegistry.Entry(ConnectionConfigStore.DEFAULT_COMMUNITY_ID, it.communityName, it.chatId))
                }.orEmpty()
            }
        val communities =
            effectiveEntries.map { entry ->
                val stored = checkNotNull(configStore.loadStored(entry.id)) { "Missing stored connection for ${entry.id}" }
                val chats =
                    chatRegistry.all(entry.id).ifEmpty {
                        listOf(ChatRegistry.Entry(stored.chatId, stored.communityName, "general"))
                    }
                val communityKey =
                    communityKeyStore.load(entry.id)
                        ?: if (effectiveEntries.size == 1) communityKeyStore.load() else null
                val rawCommunityKey = communityKey?.export()
                val encodedCommunityKey = rawCommunityKey?.let(java.util.Base64.getEncoder()::encodeToString)
                rawCommunityKey?.fill(0)
                CommunityBackup(
                    entry.id,
                    entry.name,
                    entry.chatId,
                    stored.config,
                    chats.map { ChatBackup(it.id, it.name, it.kind) },
                    encodedCommunityKey,
                    UserSettings.isHostFor(entry.id),
                    UserSettings.pollFloorFor(entry.id),
                    UserSettings.retentionDaysFor(entry.id),
                )
            }
        if (communities.isEmpty()) return null
        val active = activeStore.load(communities.first().id)
        return AccountBackup(active.takeIf { id -> communities.any { it.id == id } } ?: communities.first().id, communities)
    }

    override fun replace(backup: AccountBackup) {
        val previous = snapshot()
        try {
            writeSnapshot(backup)
        } catch (failure: Exception) {
            try {
                if (previous != null) writeSnapshot(previous) else clearSnapshot(backup)
            } catch (rollbackFailure: Exception) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }
    }

    override fun clear() {
        val ids = communityRegistry.all().map { it.id }
        clearSnapshot(AccountBackup(ids.firstOrNull() ?: ConnectionConfigStore.DEFAULT_COMMUNITY_ID, emptyList()))
    }

    private fun clearSnapshot(backup: AccountBackup) {
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

    private fun writeSnapshot(backup: AccountBackup) {
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
