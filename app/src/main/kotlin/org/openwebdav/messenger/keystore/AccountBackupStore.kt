package org.openwebdav.messenger.keystore

import android.content.Context
import org.openwebdav.messenger.crypto.Aead
import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.crypto.NativeCrypto
import org.openwebdav.messenger.export.AccountBackup
import org.openwebdav.messenger.export.AccountBackupGraveyardValidation
import org.openwebdav.messenger.export.ChatBackup
import org.openwebdav.messenger.export.ChatDeletionBackup
import org.openwebdav.messenger.export.CommunityBackup
import org.openwebdav.messenger.export.ExportableAccountBackupStore
import org.openwebdav.messenger.governance.CommunityDmParticipant
import org.openwebdav.messenger.governance.CommunityGovernanceEnvelopeCodec
import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.identity.IdentityLoadResult
import org.openwebdav.messenger.identity.IdentityStore
import org.openwebdav.messenger.ui.settings.UserSettings
import java.util.Base64

/** Bridges the encrypted account backup to the existing per-community secure and registry stores. */
internal class AccountBackupStore(
    private val context: Context,
    private val configStore: AccountBackupConfigStore = ConnectionConfigStore(context),
    private val identityCrypto: IdentityCrypto? = null,
    private val nativeCrypto: NativeCrypto? = null,
) : ExportableAccountBackupStore {
    private val communityRegistry = CommunityRegistry(context)
    private val chatRegistry = ChatRegistry(context)
    private val activeStore = ActiveCommunityStore(context)
    private val communityKeyStore = CommunityKeyStore(context)
    private val deletionState = AccountBackupDeletionState(context)
    private val trustStore = CommunityTrustStore(context)
    private val snapshotWriter =
        AccountBackupSnapshotWriter(context, configStore, communityRegistry, chatRegistry, activeStore, identityCrypto)

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
                        listOf(ChatRegistry.Entry(stored.chatId, stored.communityName, "general", "unknown"))
                    }
                val communityKey =
                    communityKeyStore.load(entry.id)
                        ?: if (effectiveEntries.size == 1) communityKeyStore.load() else null
                val rawCommunityKey = communityKey?.export()
                val encodedCommunityKey = rawCommunityKey?.let(java.util.Base64.getEncoder()::encodeToString)
                rawCommunityKey?.fill(0)
                val deletion = deletionState.snapshot(entry.id)
                val trust = trustStore.get(entry.id)
                CommunityBackup(
                    entry.id,
                    entry.name,
                    entry.chatId,
                    stored.config,
                    chats.map { ChatBackup(it.id, it.name, it.kind, it.access) },
                    encodedCommunityKey,
                    UserSettings.isHostFor(entry.id),
                    UserSettings.pollFloorFor(entry.id),
                    UserSettings.retentionDaysFor(entry.id),
                    deletion.hiddenChatIds,
                    deletion.suppressedMessageIds,
                    trust?.genesisRecord?.let(java.util.Base64.getEncoder()::encodeToString),
                    trust?.fingerprintVerified ?: false,
                    trust?.highestPolicyRevision ?: 0,
                    trust?.highestPolicyHash,
                    deletion.chatDeletions.map { row ->
                        ChatDeletionBackup(
                            row.chatId,
                            row.scope,
                            row.originMode,
                            row.originHash,
                            row.tombstoneHash,
                            java.util.Base64.getEncoder().encodeToString(row.tombstoneStoredBytes),
                            row.phase,
                            row.cleanupAttempts,
                            row.nextAttemptAtMillis,
                            row.lastError,
                            row.updatedAtMillis,
                            row.tombstonePlaintextBytes?.let(Base64.getEncoder()::encodeToString),
                            row.originPlaintextBytes?.let(Base64.getEncoder()::encodeToString),
                        )
                    },
                )
            }
        communities.forEach { community ->
            if (community.chatDeletions.isNotEmpty()) {
                val native = checkNotNull(nativeCrypto) { "Cannot export unauthenticated chat-deletion state" }
                val verifier = checkNotNull(identityCrypto) { "Cannot export unauthenticated chat-deletion state" }
                val key =
                    community.communityKeyBase64?.let { encoded ->
                        val keyBytes = Base64.getDecoder().decode(encoded)
                        try {
                            ChatKey.fromBytes(keyBytes)
                        } finally {
                            keyBytes.fill(0)
                        }
                    }
                check(community.chatDeletions.none { it.scope == "GROUP" } || key != null) {
                    "Cannot export unauthenticated group deletion state"
                }
                val expectedOwner =
                    if (community.chatDeletions.any { it.scope == "DM" }) {
                        val identity =
                            (IdentityStore(context, verifier).load() as? IdentityLoadResult.Loaded)?.identity
                                ?: error("Cannot export unauthenticated DM deletion state")
                        CommunityDmParticipant(identity.copySignPublic(), identity.copyBoxPublic())
                    } else {
                        null
                    }
                try {
                    check(
                        AccountBackupGraveyardValidation.hasAuthenticatedTombstones(
                            community.id,
                            key,
                            community.chatDeletions,
                            CommunityGovernanceEnvelopeCodec(Aead(native)),
                            verifier,
                            expectedOwner,
                        ),
                    ) { "Cannot export invalid chat-deletion state" }
                } finally {
                    expectedOwner?.signingPublicKey?.fill(0)
                    expectedOwner?.boxPublicKey?.fill(0)
                }
            }
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
