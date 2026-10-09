package org.openwebdav.messenger.governance

import org.openwebdav.messenger.identity.IdentityCrypto
import org.openwebdav.messenger.protocol.Base32
import java.security.MessageDigest

internal object CommunityTombstoneCodec {
    fun sign(
        record: CommunityTombstone,
        signingSecret: ByteArray,
        crypto: IdentityCrypto,
    ): ByteArray {
        require(validRecord(record))
        val body = CommunityTombstoneWire.encodeBody(record)
        return body + crypto.sign(signatureInput(record.scope, body), signingSecret)
    }

    fun verify(
        bytes: ByteArray,
        communityId: String,
        chatId: String,
        originHash: String,
        scope: TombstoneScope,
        crypto: IdentityCrypto,
    ): CommunityTombstone? {
        val decoded = CommunityTombstoneWire.decode(bytes) ?: return null
        val record = decoded.record
        if (record.communityId != communityId || record.chatId != chatId || record.originHash != originHash || record.scope != scope) {
            return null
        }
        if (!validRecord(record) || !crypto.verify(decoded.signature, signatureInput(scope, decoded.body), record.signerSigningPublicKey)) {
            return null
        }
        return record
    }

    fun contentAddress(bytes: ByteArray): String =
        Base32.encodeBase32Lower(MessageDigest.getInstance("SHA-256").digest(bytes)).take(HASH_CHARS)

    private fun validRecord(record: CommunityTombstone): Boolean {
        if (!GovernanceRecordSupport.isValidCommunityId(record.communityId) || !CHAT_ID.matches(record.chatId) ||
            !HASH.matches(record.originHash) || record.revision <= 0 || record.signerSigningPublicKey.size != KEY_BYTES
        ) {
            return false
        }
        return when (record.scope) {
            TombstoneScope.GROUP ->
                record.signerRole in setOf(TombstoneSignerRole.PINNED_HOST, TombstoneSignerRole.GROUP_CREATOR) &&
                    record.participants.isEmpty()
            TombstoneScope.DM ->
                record.signerRole == TombstoneSignerRole.DM_INITIATOR && record.participants.size == PARTICIPANTS &&
                    record.participants.any { it.signingPublicKey.contentEquals(record.signerSigningPublicKey) } &&
                    record.participants.all { it.signingPublicKey.size == KEY_BYTES && it.boxPublicKey.size == KEY_BYTES } &&
                    record.participants.map { it.signingPublicKey.toList() }.distinct().size == PARTICIPANTS
        }
    }

    private fun signatureInput(
        scope: TombstoneScope,
        body: ByteArray,
    ): ByteArray = (if (scope == TombstoneScope.GROUP) GROUP_DOMAIN else DM_DOMAIN) + byteArrayOf(0) + body

    private val CHAT_ID = Regex("[A-Za-z0-9_-]{1,96}")
    private val HASH = Regex("[a-z2-7]{32}")
    private val GROUP_DOMAIN = "owdm/group-tombstone/v1".toByteArray(Charsets.UTF_8)
    private val DM_DOMAIN = "owdm/dm-tombstone/v1".toByteArray(Charsets.UTF_8)
    private const val KEY_BYTES = 32
    private const val PARTICIPANTS = 2
    private const val HASH_CHARS = 32
}
