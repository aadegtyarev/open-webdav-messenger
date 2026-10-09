package org.openwebdav.messenger.governance

enum class TombstoneScope { GROUP, DM }

enum class TombstoneOriginMode { ORIGIN_HASH, LEGACY_CHAT_ID }

enum class TombstoneSignerRole { PINNED_HOST, GROUP_CREATOR, DM_INITIATOR }

data class CommunityTombstone(
    val communityId: String,
    val chatId: String,
    val originHash: String,
    val scope: TombstoneScope,
    val revision: Long,
    val signerRole: TombstoneSignerRole,
    val signerSigningPublicKey: ByteArray,
    val participants: List<CommunityDmParticipant>,
    val originMode: TombstoneOriginMode = TombstoneOriginMode.ORIGIN_HASH,
)
