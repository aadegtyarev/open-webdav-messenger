package org.openwebdav.messenger.keystore

import org.openwebdav.messenger.governance.CommunityGenesisCodec
import org.openwebdav.messenger.governance.CommunityTrustRecord
import org.openwebdav.messenger.governance.GovernanceRecordSupport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

internal object CommunityTrustStoreCodec {
    fun encode(records: Collection<CommunityTrustRecord>): ByteArray {
        require(records.size <= MAX_RECORDS && records.map { it.communityId }.distinct().size == records.size)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.write(MAGIC)
            output.writeInt(VERSION)
            output.writeInt(records.size)
            records.sortedBy { it.communityId }.forEach { record ->
                require(isValidId(record.communityId) && HASH.matches(record.genesisHash))
                require(record.genesisRecord.size in 1..MAX_GENESIS_BYTES)
                require(CommunityGenesisCodec.contentAddress(record.genesisRecord) == record.genesisHash)
                require(
                    record.hostSigningPublicKey.size == KEY_BYTES && record.hostBoxPublicKey.size == KEY_BYTES,
                )
                require(record.highestPolicyRevision >= 0)
                val validPolicyHash =
                    if (record.highestPolicyRevision == 0L) {
                        record.highestPolicyHash == null
                    } else {
                        record.highestPolicyHash?.let(HASH::matches) == true
                    }
                require(validPolicyHash)
                output.writeUTF(record.communityId)
                output.writeUTF(record.genesisHash)
                output.writeShort(record.genesisRecord.size)
                output.write(record.genesisRecord)
                output.write(record.hostSigningPublicKey)
                output.write(record.hostBoxPublicKey)
                output.writeBoolean(record.fingerprintVerified)
                output.writeLong(record.highestPolicyRevision)
                output.writeBoolean(record.highestPolicyHash != null)
                record.highestPolicyHash?.let(output::writeUTF)
            }
        }
        return bytes.toByteArray().also { require(it.size <= MAX_BYTES) }
    }

    fun decode(bytes: ByteArray): List<CommunityTrustRecord>? =
        runCatching {
            require(bytes.size <= MAX_BYTES)
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(GovernanceRecordSupport.readExactly(input, MAGIC.size).contentEquals(MAGIC))
                require(input.readInt() == VERSION)
                val count = input.readInt().also { require(it in 0..MAX_RECORDS) }
                val records =
                    List(count) {
                        val id = input.readUTF().also { require(isValidId(it)) }
                        val genesisHash = input.readUTF().also { require(HASH.matches(it)) }
                        val genesisSize = input.readUnsignedShort().also { require(it in 1..MAX_GENESIS_BYTES) }
                        val genesis = GovernanceRecordSupport.readExactly(input, genesisSize).also { require(it.size == genesisSize) }
                        require(CommunityGenesisCodec.contentAddress(genesis) == genesisHash)
                        val signKey = GovernanceRecordSupport.readExactly(input, KEY_BYTES).also { require(it.size == KEY_BYTES) }
                        val boxKey = GovernanceRecordSupport.readExactly(input, KEY_BYTES).also { require(it.size == KEY_BYTES) }
                        val verified = input.readBoolean()
                        val revision = input.readLong().also { require(it >= 0) }
                        val policyHash = if (input.readBoolean()) input.readUTF().also { require(HASH.matches(it)) } else null
                        require((revision == 0L) == (policyHash == null))
                        CommunityTrustRecord(id, genesisHash, genesis, signKey, boxKey, verified, revision, policyHash)
                    }
                require(records.map { it.communityId }.distinct().size == records.size && input.available() == 0)
                records
            }
        }.getOrNull()

    private fun isValidId(value: String): Boolean = value.matches(Regex("[A-Za-z0-9_-]{1,96}"))

    private val MAGIC = "OWCT".toByteArray(Charsets.US_ASCII)
    private val HASH = Regex("[a-z2-7]{32}")
    private const val VERSION = 2
    private const val KEY_BYTES = 32
    private const val MAX_GENESIS_BYTES = 512
    private const val MAX_RECORDS = 100
    private const val MAX_BYTES = 64 * 1024
}
