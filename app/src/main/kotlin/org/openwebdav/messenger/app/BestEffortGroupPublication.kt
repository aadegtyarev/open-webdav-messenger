package org.openwebdav.messenger.app

import kotlinx.coroutines.CancellationException

internal suspend fun bestEffortGroupPublication(publish: suspend () -> Boolean): Boolean =
    try {
        publish()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        true
    }
