package org.openwebdav.messenger.keystore

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ChatKeyIndexTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun failed_add_and_delete_index_writes_propagate() {
        val file = File(temporaryFolder.root, "ids.txt")
        val id = "chat-1"
        val index = ChatKeyIndex(file) { _, _ -> throw IOException("injected index failure") }
        // A pre-existing entry makes remove reach the failing writer as well.
        file.writeText("$id\t\n")
        org.junit.Assert.assertThrows(IOException::class.java) { index.add("chat-2") }
        org.junit.Assert.assertThrows(IOException::class.java) { index.remove(id) }
    }

    @Test
    fun index_round_trips_identifiers_and_filters_missing_key_files() {
        val file = File(temporaryFolder.root, "ids.txt")
        val index = ChatKeyIndex(file)
        index.add("chat-1")
        index.add("chat-2")
        assertEquals(listOf("chat-1"), index.list { it == "chat-1" })
        index.remove("chat-1")
        assertEquals(listOf("chat-2"), index.list { true })
    }
}
