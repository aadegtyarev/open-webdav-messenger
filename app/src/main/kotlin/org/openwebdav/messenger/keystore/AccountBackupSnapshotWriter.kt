package org.openwebdav.messenger.keystore

import android.content.Context
import org.openwebdav.messenger.export.AccountBackup
import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.ui.settings.UserSettings
import java.util.Base64

/** Applies account-backup snapshots to the existing stores. */
internal class AccountBackupSnapshotWriter(
    private val context: Context,
    private val configStore: AccountBackupConfigStore,
    private val communityRegistry: CommunityRegistry,
    private val chatRegistry: ChatRegistry,
    private val activeStore: ActiveCommunityStore,
    private val identityCrypto: IdentityCrypto? = null,
    private val trustStore: CommunityTrustBackupStore = CommunityTrustStore(context),
) {
    private val deletionState = AccountBackupDeletionState(context)

    fun clear(extraIds: Set<String>) {
        val ids = (communityRegistry.all().map { it.id } + extraIds + ConnectionConfigStore.DEFAULT_COMMUNITY_ID).distinct()
        ids.forEach(configStore::clear)
        ids.forEach(chatRegistry::clear)
        ids.forEach(UserSettings::clearCommunitySettingsStrict)
        deletionState.clear(ids.toSet())
        ids.forEach(trustStore::remove)
        communityRegistry.replace(emptyList())
        activeStore.clear()
        UserSettings.selectCommunity("default")
    }

    fun write(backup: AccountBackup) {
        val existingCommunityIds = (communityRegistry.all().map { it.id } + configStore.listCommunityIds()).toSet()
        val previousIds = existingCommunityIds + ConnectionConfigStore.DEFAULT_COMMUNITY_ID
        val replacementIds = backup.communities.mapTo(mutableSetOf()) { it.id }
        (previousIds + replacementIds).forEach(UserSettings::clearCommunitySettingsStrict)
        (previousIds - replacementIds).forEach(configStore::clear)
        (previousIds - replacementIds).forEach(chatRegistry::clear)
        deletionState.clear(previousIds - replacementIds)
        (previousIds - replacementIds).forEach(trustStore::remove)
        backup.communities.forEach { community ->
            configStore.save(community.config, community.anchorChatId, community.name, community.id)
            chatRegistry.replace(community.id, community.chats.map { ChatRegistry.Entry(it.id, it.name, it.kind, it.access) })
            deletionState.replace(
                community.id,
                community.hiddenDmChatIds,
                community.suppressedDmMessageIds,
                community.chatDeletions,
            )
            val genesis = community.genesisRecordBase64
            if (genesis == null) {
                if (community.id !in existingCommunityIds) trustStore.remove(community.id)
            } else {
                trustStore.restoreRecord(
                    community.id,
                    Base64.getDecoder().decode(genesis),
                    community.hostFingerprintVerified,
                    community.highestPolicyRevision,
                    community.highestPolicyHash,
                    checkNotNull(identityCrypto) { "Identity crypto required to restore community trust" },
                )
            }
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
