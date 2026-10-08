package org.openwebdav.messenger.keystore

import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class StrictFileOperationsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun failed_delete_is_reported() {
        val directory = File(temporaryFolder.root, "non-empty").apply { mkdir() }
        File(directory, "kept").writeText("data")
        assertThrows(IOException::class.java) { StrictFileOperations.delete(directory) }
    }
}
