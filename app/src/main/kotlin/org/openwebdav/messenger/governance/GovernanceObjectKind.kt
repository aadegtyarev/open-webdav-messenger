package org.openwebdav.messenger.governance

enum class GovernanceObjectKind(val wireValue: Byte) {
    GENESIS(1),
    POLICY(2),
    GROUP_ORIGIN(3),
    DM_ORIGIN(4),
    GROUP_TOMBSTONE(5),
    DM_TOMBSTONE(6),
}
