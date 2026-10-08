package org.openwebdav.messenger.export

/** Signals that a backup exceeds the shared import/export payload bound. */
internal class PayloadTooLargeException : Exception("Backup exceeds the maximum supported size")
