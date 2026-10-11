package org.openwebdav.messenger.app

internal enum class GenesisMigrationResult {
    Published,
    AlreadyPublished,
    NotHost,
    Conflict,
    Unavailable,
}
