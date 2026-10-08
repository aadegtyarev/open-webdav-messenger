package org.openwebdav.messenger.app

import org.openwebdav.messenger.directory.DirectoryEntry
import org.openwebdav.messenger.keystore.AccountIdentifier
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

internal object RosterCacheCodec {
    const val MAX_FILE_BYTES = 256 * 1024
    const val MAX_CACHE_ENTRIES = 128
    const val MAX_MEMBERS = 512
    private const val VERSION = 1
    private val MAGIC = byteArrayOf(0x52, 0x53, 0x54, 0x52)

    fun decode(bytes: ByteArray): List<CachedVerifiedRoster>? {
        if (bytes.size > MAX_FILE_BYTES) return null
        return runCatching {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(ByteArray(MAGIC.size).also(input::readFully).contentEquals(MAGIC))
                require(input.readUnsignedByte() == VERSION)
                val count = input.readUnsignedShort()
                require(count <= MAX_CACHE_ENTRIES)
                val entries =
                    List(count) {
                        val community = readText(input, 96)
                        val chat = readText(input, 96)
                        require(AccountIdentifier.isValid(community) && AccountIdentifier.isValid(chat))
                        val provenance = ByteArray(32).also(input::readFully)
                        val memberCount = input.readUnsignedShort()
                        require(memberCount <= MAX_MEMBERS)
                        val signers = HashSet<String>()
                        val members =
                            List(memberCount) {
                                val name = readText(input, 256)
                                val signer = ByteArray(32).also(input::readFully)
                                val box = ByteArray(32).also(input::readFully)
                                require(signers.add(signer.toHex()))
                                DirectoryEntry(name, signer, box)
                            }
                        CachedVerifiedRoster(community, chat, provenance, members)
                    }
                require(entries.map { it.communityId to it.chatId }.distinct().size == entries.size)
                require(input.available() == 0)
                entries
            }
        }.getOrNull()
    }

    fun encode(entries: List<CachedVerifiedRoster>): ByteArray {
        require(entries.map { it.communityId to it.chatId }.distinct().size == entries.size)
        RosterCacheSize.requireBounded(entries)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.write(MAGIC)
            out.writeByte(VERSION)
            out.writeShort(entries.size)
            entries.forEach { entry ->
                require(AccountIdentifier.isValid(entry.communityId) && AccountIdentifier.isValid(entry.chatId))
                require(entry.provenance.size == 32 && entry.entries.size <= MAX_MEMBERS)
                writeText(out, entry.communityId, 96)
                writeText(out, entry.chatId, 96)
                out.write(entry.provenance)
                out.writeShort(entry.entries.size)
                val signers = HashSet<String>()
                entry.entries.forEach { member ->
                    val signer = member.copySigningPublicKey()
                    require(signer.size == 32 && member.copyBoxPublicKey().size == 32)
                    require(signers.add(signer.toHex()))
                    writeText(out, member.displayName, 256)
                    out.write(signer)
                    out.write(member.copyBoxPublicKey())
                }
            }
        }
        return bytes.toByteArray().also { require(it.size <= MAX_FILE_BYTES) }
    }

    private fun writeText(
        out: DataOutputStream,
        value: String,
        maxBytes: Int,
    ) {
        val bytes = RosterCacheText.encode(value, maxBytes)
        out.writeShort(bytes.size)
        out.write(bytes)
    }

    private fun readText(
        input: DataInputStream,
        maxBytes: Int,
    ): String {
        val size = input.readUnsignedShort()
        require(size <= maxBytes && size <= input.available())
        return RosterCacheText.decode(ByteArray(size).also(input::readFully))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
