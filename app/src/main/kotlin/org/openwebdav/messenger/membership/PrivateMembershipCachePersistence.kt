package org.openwebdav.messenger.membership

internal interface PrivateMembershipCachePersistence {
    fun loadAll(): List<PrivateMembershipCacheRecord>?

    fun replaceAll(records: List<PrivateMembershipCacheRecord>)

    fun clear()
}
