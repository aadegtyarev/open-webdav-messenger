package org.openwebdav.messenger.export

/** The typed result of an export operation — either the ready-to-share base64 blob, or a rejection. */
sealed interface ExportResult {
    /** Export succeeded; [blob] is the base64-encoded, encrypted export. Ready for ACTION_SEND. */
    data class Ready(val blob: String) : ExportResult

    /** The password was empty or too short (< 8 chars). */
    data object WeakPassword : ExportResult
}

/** The typed result of a restore operation. */
sealed interface RestoreResult {
    /** Restore succeeded. */
    data object Restored : RestoreResult

    /** The blob does not start with the expected magic header — not a valid export file. */
    data object BadFormat : RestoreResult

    /** Wrong password (AEAD authentication failure) or the blob was tampered with. */
    data object WrongPasswordOrTampered : RestoreResult

    /** The inner JSON payload is structurally invalid (wrong version, corrupt format). */
    data object CorruptPayload : RestoreResult

    /** User cancelled or password empty. */
    data object WeakPassword : RestoreResult
}
