package org.openwebdav.messenger.governance

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

internal object CommunityDmOriginWire {
    data class Decoded(val origin: CommunityDmOrigin, val body: ByteArray, val signature: ByteArray)

    fun encodeBody(origin: CommunityDmOrigin): ByteArray {
        require(GovernanceRecordSupport.isValidCommunityId(origin.communityId) && CHAT_ID.matches(origin.chatId))
        require(origin.participants.size == PARTICIPANT_COUNT && origin.nonce.size == NONCE_BYTES)
        require(origin.participants.all { it.signingPublicKey.size == KEY_BYTES && it.boxPublicKey.size == KEY_BYTES })
        require(origin.participants.map { it.signingPublicKey.toList() }.distinct().size == PARTICIPANT_COUNT)
        require(origin.participants.any { it.signingPublicKey.contentEquals(origin.initiatorSigningPublicKey) })
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(MAGIC)
                output.writeByte(VERSION)
                writeText(output, origin.communityId)
                writeText(output, origin.chatId)
                val participants = CommunityDmOriginKeyOrder.sort(origin.participants)
                output.writeByte(participants.size)
                participants.forEach { participant ->
                    output.write(participant.signingPublicKey)
                    output.write(participant.boxPublicKey)
                }
                output.write(origin.initiatorSigningPublicKey)
                output.write(origin.nonce)
            }
        }.toByteArray()
    }

    fun decode(bytes: ByteArray): Decoded? {
        if (bytes.size !in MIN_BYTES..MAX_BYTES) return null
        return runCatching {
            DataInputStream(bytes.inputStream()).use { input ->
                require(GovernanceRecordSupport.readExactly(input, MAGIC.size).contentEquals(MAGIC))
                require(input.readUnsignedByte() == VERSION)
                val communityId = readText(input).also { require(GovernanceRecordSupport.isValidCommunityId(it)) }
                val chatId = readText(input).also { require(CHAT_ID.matches(it)) }
                val count = input.readUnsignedByte().also { require(it == PARTICIPANT_COUNT) }
                val participants = List(count) { CommunityDmParticipant(readKey(input), readKey(input)) }
                val keys = participants.map { it.signingPublicKey.toList() }
                require(keys == keys.sortedWith(CommunityDmOriginKeyOrder.lists) && keys.distinct().size == count)
                val initiator = readKey(input)
                require(participants.any { it.signingPublicKey.contentEquals(initiator) })
                val nonce = GovernanceRecordSupport.readExactly(input, NONCE_BYTES)
                val signature = GovernanceRecordSupport.readExactly(input, SIGNATURE_BYTES)
                require(input.available() == 0)
                Decoded(
                    CommunityDmOrigin(communityId, chatId, participants, initiator, nonce),
                    bytes.copyOfRange(0, bytes.size - SIGNATURE_BYTES),
                    signature,
                )
            }
        }.getOrNull()
    }

    fun signatureInput(body: ByteArray): ByteArray = DOMAIN + byteArrayOf(0) + body

    private fun readKey(input: DataInputStream): ByteArray = GovernanceRecordSupport.readExactly(input, KEY_BYTES)

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

    private val MAGIC = "OWDO".toByteArray(Charsets.US_ASCII)
    private val DOMAIN = "owdm/dm-origin/v1".toByteArray(Charsets.UTF_8)
    private val CHAT_ID = Regex("[A-Za-z0-9_-]{1,96}")
    private const val VERSION = 1
    private const val KEY_BYTES = 32
    private const val NONCE_BYTES = 32
    private const val SIGNATURE_BYTES = 64
    private const val PARTICIPANT_COUNT = 2
    private const val MAX_TEXT_BYTES = 96
    private const val MIN_BYTES = 4 + 1 + 2 + 1 + 2 + 1 + 1 + 2 * KEY_BYTES * 2 + KEY_BYTES + NONCE_BYTES + SIGNATURE_BYTES
    private const val MAX_BYTES = MIN_BYTES + 2 * MAX_TEXT_BYTES
}
