package org.openwebdav.messenger.keystore

import java.io.File
import java.io.IOException

/** File operations whose failure must be visible to account replacement and rollback callers. */
internal object StrictFileOperations {
    fun delete(file: File) {
        if (file.exists() && (!file.delete() || file.exists())) {
            throw IOException("Could not delete stored file")
        }
    }
}
