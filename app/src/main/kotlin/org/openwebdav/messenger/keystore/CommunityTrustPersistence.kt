package org.openwebdav.messenger.keystore

import org.openwebdav.messenger.governance.CommunityTrustRecord

internal interface CommunityTrustPersistence {
    fun load(): List<CommunityTrustRecord>

    fun save(records: List<CommunityTrustRecord>)
}
