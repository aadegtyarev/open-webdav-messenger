package org.openwebdav.messenger.app

import java.util.concurrent.atomic.AtomicLong

/** Invalidates asynchronous chat opens when a newer community/runtime selection begins. */
internal class RuntimeSelectionGuard {
    private val revision = AtomicLong(0)

    fun current(): Long = revision.get()

    fun begin(): Long = revision.incrementAndGet()

    fun isCurrent(expectedRevision: Long): Boolean = revision.get() == expectedRevision
}
