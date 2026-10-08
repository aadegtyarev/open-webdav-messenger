package org.openwebdav.messenger.keystore

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Strict plaintext index for enumerating wrapped per-chat key files. */
internal class ChatKeyIndex(
    private val file: File,
    private val persist: (File, ByteArray) -> Unit = ::persistIndex,
) {
    fun list(hasKey: (String) -> Boolean): List<String> = read().filter(hasKey)

    fun add(chatId: String) {
        AccountIdentifier.requireValid(chatId)
        write((read().filterNot { it == chatId } + chatId))
    }

    fun remove(chatId: String) {
        val ids = read()
        if (chatId in ids) write(ids.filterNot { it == chatId })
    }

    private fun read(): List<String> {
        if (!file.exists()) return emptyList()
        return file.readLines().map { line ->
            val id = line.removeSuffix("\t")
            if ((line != id && line != "$id\t") || !AccountIdentifier.isValid(id)) throw IOException("Invalid chat-key index")
            id
        }.distinct()
    }

    private fun write(ids: List<String>) {
        persist(file, ids.joinToString(separator = "\n", postfix = if (ids.isEmpty()) "" else "\n").toByteArray())
    }
}

private fun persistIndex(
    file: File,
    bytes: ByteArray,
) {
    file.parentFile?.mkdirs()
    FileOutputStream(file).use { output ->
        output.write(bytes)
        output.fd.sync()
    }
}
