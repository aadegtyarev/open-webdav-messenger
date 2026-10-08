package org.openwebdav.messenger.keystore

/** Runs all independent store operations, then propagates the combined failure. */
internal class StrictOperationBatch {
    private var failure: Exception? = null

    fun run(operation: () -> Unit) {
        try {
            operation()
        } catch (next: Exception) {
            val previous = failure
            if (previous == null) failure = next else previous.addSuppressed(next)
        }
    }

    fun finish() {
        failure?.let { throw it }
    }
}
