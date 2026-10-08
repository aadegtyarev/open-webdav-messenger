package org.openwebdav.messenger.membership

import org.openwebdav.messenger.keystore.AccountIdentifier
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Strict bounds and canonical UTF-8 for the encrypted private-roster cache. */
internal object PrivateMembershipCacheCodec {
    const val MAX_BYTES = 256 * 1024
    private const val VERSION = 1
    private val MAGIC = byteArrayOf(0x50, 0x4d, 0x43, 0x48)

    fun decode(bytes: ByteArray): List<PrivateMembershipCacheRecord>? {
        if (bytes.size > MAX_BYTES) return null
        return runCatching {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(ByteArray(4).also(input::readFully).contentEquals(MAGIC) && input.readUnsignedByte() == VERSION)
                val records =
                    List(input.readUnsignedShort().also { require(it <= 128) }) {
                        val community = readText(input, 96)
                        val chat = readText(input, 96)
                        require(AccountIdentifier.isValid(community) && AccountIdentifier.isValid(chat))
                        val provenance = ByteArray(32).also(input::readFully)
                        val members =
                            List(input.readUnsignedShort().also { require(it <= PrivateMembershipFormat.MAX_LISTED_ENTRIES) }) {
                                val name = readText(input, PrivateMembershipFormat.MAX_DISPLAY_NAME_BYTES)
                                val signing = ByteArray(32).also(input::readFully)
                                val box = ByteArray(32).also(input::readFully)
                                PrivateMembershipCacheMember(name, signing, box, input.readBoolean())
                            }
                        require(members.map { it.signingPublicKey.toList() }.distinct().size == members.size)
                        PrivateMembershipCacheRecord(community, chat, provenance, members)
                    }
                require(records.map { it.communityId to it.chatId }.distinct().size == records.size && input.available() == 0)
                records
            }
        }.getOrNull()
    }

    fun encode(records: List<PrivateMembershipCacheRecord>): ByteArray {
        require(records.size <= 128 && records.map { it.communityId to it.chatId }.distinct().size == records.size)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.write(MAGIC)
            out.writeByte(VERSION)
            out.writeShort(records.size)
            records.forEach { record ->
                require(AccountIdentifier.isValid(record.communityId) && AccountIdentifier.isValid(record.chatId))
                require(record.provenance.size == 32 && record.members.size <= PrivateMembershipFormat.MAX_LISTED_ENTRIES)
                writeText(out, record.communityId, 96)
                writeText(out, record.chatId, 96)
                out.write(record.provenance)
                out.writeShort(record.members.size)
                record.members.forEach { member ->
                    require(member.signingPublicKey.size == 32 && member.boxPublicKey.size == 32)
                    writeText(out, member.displayName, PrivateMembershipFormat.MAX_DISPLAY_NAME_BYTES)
                    out.write(member.signingPublicKey)
                    out.write(member.boxPublicKey)
                    out.writeBoolean(member.directoryVerified)
                }
            }
        }
        return bytes.toByteArray().also { require(it.size <= MAX_BYTES) }
    }

    private fun writeText(
        out: DataOutputStream,
        value: String,
        max: Int,
    ) {
        val raw = value.toByteArray(Charsets.UTF_8)
        require(raw.size <= max)
        out.writeShort(raw.size)
        out.write(raw)
    }

    private fun readText(
        input: DataInputStream,
        max: Int,
    ): String {
        val size = input.readUnsignedShort()
        require(size <= max && size <= input.available())
        val raw = ByteArray(size).also(input::readFully)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
    }
}
