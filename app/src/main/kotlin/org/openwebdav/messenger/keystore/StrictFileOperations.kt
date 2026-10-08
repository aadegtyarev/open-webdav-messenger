package org.openwebdav.messenger.keystore

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** File operations whose failure must be visible to account replacement and rollback callers. */
internal object StrictFileOperations {
    fun atomicReplace(
        source: File,
        target: File,
    ) {
        Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    fun delete(file: File) {
        if (file.exists() && (!file.delete() || file.exists())) {
            throw IOException("Could not delete stored file")
        }
    }
}
