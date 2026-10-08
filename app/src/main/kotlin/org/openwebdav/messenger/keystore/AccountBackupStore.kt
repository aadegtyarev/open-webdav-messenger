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
    private val snapshotWriter = AccountBackupSnapshotWriter(configStore, communityRegistry, chatRegistry, activeStore)

    override fun hasMembershipState(): Boolean = hasRegistryState() || configStore.hasAny()

    override fun hasRegistryState(): Boolean = communityRegistry.all().isNotEmpty() || chatRegistry.hasAny()

    override fun snapshot(): AccountBackup? {
        val entries = communityRegistry.all()
        val effectiveEntries =
            entries.ifEmpty {
                configStore.loadStored()?.takeIf { it.chatId.isNotBlank() }?.let {
                    listOf(CommunityRegistry.Entry(ConnectionConfigStore.DEFAULT_COMMUNITY_ID, it.communityName, it.chatId))
                }.orEmpty()
            }
        val registeredIds = effectiveEntries.mapTo(mutableSetOf()) { it.id }
        check(configStore.listCommunityIds().all { it in registeredIds }) { "Stored connection has no registered community" }
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
            snapshotWriter.write(backup)
        } catch (failure: Exception) {
            try {
                if (previous != null) {
                    snapshotWriter.write(
                        previous,
                    )
                } else {
                    snapshotWriter.clear(backup.communities.mapTo(mutableSetOf()) { it.id })
                }
            } catch (rollbackFailure: Exception) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }
    }

    override fun clear() = clearCommunityIds(emptySet())

    override fun clearCommunityIds(ids: Set<String>) = snapshotWriter.clear(ids)
}
