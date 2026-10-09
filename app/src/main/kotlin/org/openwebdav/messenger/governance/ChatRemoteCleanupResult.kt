package org.openwebdav.messenger.governance

data class ChatRemoteCleanupResult(val deletedObjects: Int, val pendingReasons: List<String>) {
    val complete: Boolean get() = pendingReasons.isEmpty()
}
