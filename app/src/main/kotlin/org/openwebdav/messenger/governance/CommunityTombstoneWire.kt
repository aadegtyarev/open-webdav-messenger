package org.openwebdav.messenger.governance

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

internal object CommunityTombstoneWire {
    data class Decoded(val record: CommunityTombstone, val body: ByteArray, val signature: ByteArray)

    fun encodeBody(record: CommunityTombstone): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(MAGIC)
                output.writeByte(VERSION)
                output.writeByte(if (record.scope == TombstoneScope.GROUP) 0 else 1)
                output.writeByte(record.signerRole.ordinal)
                writeText(output, record.communityId)
                writeText(output, record.chatId)
                output.write(record.originHash.toByteArray(Charsets.US_ASCII))
                output.writeLong(record.revision)
                output.write(record.signerSigningPublicKey)
                output.writeByte(record.participants.size)
                val participants = CommunityDmOriginKeyOrder.sort(record.participants)
                participants.forEach {
                    output.write(it.signingPublicKey)
                    output.write(it.boxPublicKey)
                }
            }
        }.toByteArray()

    fun decode(bytes: ByteArray): Decoded? {
        if (bytes.size !in MIN_BYTES..MAX_BYTES) return null
        return runCatching {
            DataInputStream(bytes.inputStream()).use { input ->
                require(GovernanceRecordSupport.readExactly(input, MAGIC.size).contentEquals(MAGIC))
                require(input.readUnsignedByte() == VERSION)
                val scope =
                    when (input.readUnsignedByte()) {
                        0 -> TombstoneScope.GROUP
                        1 -> TombstoneScope.DM
                        else -> error("invalid tombstone scope")
                    }
                val role = TombstoneSignerRole.entries.getOrNull(input.readUnsignedByte()) ?: error("invalid signer role")
                val communityId = readText(input)
                val chatId = readText(input)
                val originHash = GovernanceRecordSupport.readExactly(input, HASH_BYTES).toString(Charsets.US_ASCII)
                val revision = input.readLong()
                val signer = readKey(input)
                val count = input.readUnsignedByte().also { require(it in 0..PARTICIPANTS) }
                val participants = List(count) { CommunityDmParticipant(readKey(input), readKey(input)) }
                val participantKeys = participants.map { it.signingPublicKey.toList() }
                require(participantKeys == participantKeys.sortedWith(CommunityDmOriginKeyOrder.lists))
                require(participantKeys.distinct().size == count)
                val signature = readKey(input, SIGNATURE_BYTES)
                require(input.available() == 0)
                Decoded(
                    CommunityTombstone(communityId, chatId, originHash, scope, revision, role, signer, participants),
                    bytes.copyOfRange(0, bytes.size - SIGNATURE_BYTES),
                    signature,
                )
            }
        }.getOrNull()
    }

    private fun readKey(
        input: DataInputStream,
        size: Int = KEY_BYTES,
    ): ByteArray = GovernanceRecordSupport.readExactly(input, size)

    private fun writeText(
        output: DataOutputStream,
        value: String,
    ) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        output.writeShort(bytes.size)
        output.write(bytes)
    }

    private fun readText(input: DataInputStream): String {
        val size = input.readUnsignedShort().also { require(it in 1..MAX_TEXT_BYTES) }
        return GovernanceRecordSupport.strictUtf8(GovernanceRecordSupport.readExactly(input, size))
    }

    private val MAGIC = "OWTS".toByteArray(Charsets.US_ASCII)
    private const val VERSION = 1
    private const val KEY_BYTES = 32
    private const val SIGNATURE_BYTES = 64
    private const val HASH_BYTES = 32
    private const val PARTICIPANTS = 2
    private const val MAX_TEXT_BYTES = 96
    private const val MIN_BYTES = 4 + 1 + 1 + 1 + 2 + 1 + 2 + 1 + HASH_BYTES + 8 + KEY_BYTES + 1 + SIGNATURE_BYTES
    private const val MAX_BYTES = MIN_BYTES + 2 * MAX_TEXT_BYTES + PARTICIPANTS * KEY_BYTES * 2
}
