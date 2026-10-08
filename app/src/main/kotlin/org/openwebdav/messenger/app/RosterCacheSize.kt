package org.openwebdav.messenger.app

internal object RosterCacheSize {
    fun requireBounded(entries: List<CachedVerifiedRoster>) {
        require(entries.size <= RosterCacheCodec.MAX_CACHE_ENTRIES)
        var total = 7L
        entries.forEach { roster ->
            require(roster.entries.size <= RosterCacheCodec.MAX_MEMBERS && roster.provenance.size == 32)
            total += 2L + RosterCacheText.encode(roster.communityId, 96).size
            total += 2L + RosterCacheText.encode(roster.chatId, 96).size + 34L
            roster.entries.forEach { member -> total += 66L + RosterCacheText.encode(member.displayName, 256).size }
            require(total <= RosterCacheCodec.MAX_FILE_BYTES)
        }
    }
}
