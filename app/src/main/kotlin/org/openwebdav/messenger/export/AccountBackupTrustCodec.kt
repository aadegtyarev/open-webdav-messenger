package org.openwebdav.messenger.export

import java.io.DataInput
import java.io.DataOutput
import java.util.Base64

internal object AccountBackupTrustCodec {
    fun write(
        output: DataOutput,
        community: CommunityBackup,
    ) {
        val genesis = community.genesisRecordBase64
        val noPolicyState = community.highestPolicyRevision == 0L && community.highestPolicyHash == null
        require(genesis != null || (!community.hostFingerprintVerified && noPolicyState))
        require(!community.hostFingerprintVerified || genesis != null)
        require(community.highestPolicyRevision >= 0)
        val validPolicyHash =
            if (community.highestPolicyRevision == 0L) {
                community.highestPolicyHash == null
            } else {
                community.highestPolicyHash?.let(HASH::matches) == true
            }
        require(validPolicyHash)
        genesis?.let { encoded ->
            val bytes = Base64.getDecoder().decode(encoded)
            require(bytes.size in 1..MAX_GENESIS_BYTES)
            output.writeBoolean(true)
            output.writeUTF(encoded)
        } ?: output.writeBoolean(false)
        output.writeBoolean(community.hostFingerprintVerified)
        output.writeLong(community.highestPolicyRevision)
        output.writeBoolean(community.highestPolicyHash != null)
        community.highestPolicyHash?.let(output::writeUTF)
    }

    fun read(input: DataInput): CommunityTrustBackup {
        val genesis = if (input.readBoolean()) input.readUTF().also(::requireValidGenesis) else null
        val verified = input.readBoolean()
        val revision = input.readLong().also { require(it >= 0) }
        val policyHash = if (input.readBoolean()) input.readUTF().also { require(HASH.matches(it)) } else null
        require(genesis != null || (!verified && revision == 0L && policyHash == null))
        require(!verified || genesis != null)
        require((revision == 0L) == (policyHash == null))
        return CommunityTrustBackup(genesis ?: "", verified, revision, policyHash)
    }

    private fun requireValidGenesis(encoded: String) {
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size in 1..MAX_GENESIS_BYTES)
    }

    private val HASH = Regex("[a-z2-7]{32}")
    private const val MAX_GENESIS_BYTES = 512
}
