package org.openwebdav.messenger.membership

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

internal object PendingPrivateClaimCodec {
    const val MAX_BYTES = PrivateMembershipFormat.MAX_FILE_BYTES + 48
    private const val VERSION = 1
    private val MAGIC = byteArrayOf(0x50, 0x4d, 0x50, 0x4e)

    fun encode(
        digest: ByteArray,
        record: PendingPrivateClaim,
    ): ByteArray {
        require(record.fileBytes.size in 1..PrivateMembershipFormat.MAX_FILE_BYTES && digest.size == 32)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.write(MAGIC)
            out.writeByte(VERSION)
            out.writeBoolean(record.uploaded)
            out.write(digest)
            out.writeShort(record.fileBytes.size)
            out.write(record.fileBytes)
        }
        return bytes.toByteArray().also { require(it.size <= MAX_BYTES) }
    }

    fun decode(bytes: ByteArray): Pair<PendingPrivateClaim, ByteArray>? {
        if (bytes.size > MAX_BYTES) return null
        return runCatching {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(ByteArray(4).also(input::readFully).contentEquals(MAGIC) && input.readUnsignedByte() == VERSION)
                val uploaded = input.readBoolean()
                val digest = ByteArray(32).also(input::readFully)
                val size = input.readUnsignedShort().also { require(it in 1..PrivateMembershipFormat.MAX_FILE_BYTES) }
                val claim = ByteArray(size).also(input::readFully)
                require(input.available() == 0)
                PendingPrivateClaim(claim, uploaded) to digest
            }
        }.getOrNull()
    }
}
