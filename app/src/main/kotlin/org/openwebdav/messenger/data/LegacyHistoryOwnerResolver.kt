package org.openwebdav.messenger.data

import org.openwebdav.messenger.keystore.AccountIdentifier
import org.openwebdav.messenger.keystore.CommunityRegistry
import org.openwebdav.messenger.keystore.StoredConnection

internal object LegacyHistoryOwnerResolver {
    fun resolve(
        registryEntries: Collection<CommunityRegistry.Entry>,
        physicalConfigIds: Collection<String>,
        loadStored: (String) -> StoredConnection?,
    ): String? {
        val configs = mutableMapOf<String, StoredConnection>()
        for (id in physicalConfigIds.distinct()) {
            if (!isValidOwnerId(id)) return null
            val stored =
                try {
                    loadStored(id)
                } catch (_: Exception) {
                    null
                } ?: return null
            if (stored.chatId.isNotBlank() && !AccountIdentifier.isValid(stored.chatId)) return null
            configs[id] = stored
        }

        if (registryEntries.any { !isValidOwnerId(it.id) || !AccountIdentifier.isValid(it.chatId) }) return null
        val registryById = registryEntries.groupBy { it.id }
        if (registryById.values.any { entries -> entries.map { it.chatId }.distinct().size != 1 }) return null
        for ((id, entries) in registryById) {
            val stored = configs[id] ?: return null
            if (stored.chatId.isBlank() || stored.chatId != entries.first().chatId) return null
        }

        val joinedIds = registryById.keys + configs.filterValues { it.chatId.isNotBlank() }.keys
        return joinedIds.singleOrNull()
    }

    private fun isValidOwnerId(id: String): Boolean = id != MessengerDatabase.LEGACY_UNSCOPED_COMMUNITY_ID && AccountIdentifier.isValid(id)
}
