package org.openwebdav.messenger.app

import org.openwebdav.messenger.data.ChatDeletionEntity

/** Runs a bounded oldest-due-first retry batch and persists capped exponential backoff. */
internal class ChatDeletionRetryQueue(
    private val loadDue: suspend (Long, Int) -> List<ChatDeletionEntity>,
    private val process: suspend (ChatDeletionEntity) -> String?,
    private val saveFailure: suspend (ChatDeletionEntity, Int, Long, String, Long) -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(limit: Int = DEFAULT_BATCH_SIZE): Int {
        val now = nowMillis()
        var completed = 0
        for (row in loadDue(now, limit.coerceIn(1, MAX_BATCH_SIZE))) {
            val error = runCatching { process(row) }.getOrElse { it.message ?: "retry failed" }
            if (error == null) {
                completed++
            } else {
                val attempts = row.cleanupAttempts + 1
                val delay = minOf(MAX_DELAY_MILLIS, BASE_DELAY_MILLIS shl attempts.coerceAtMost(MAX_SHIFT))
                val next = if (Long.MAX_VALUE - now < delay) Long.MAX_VALUE else now + delay
                saveFailure(row, attempts, next, error.take(MAX_ERROR_LENGTH), now)
            }
        }
        return completed
    }

    private companion object {
        const val DEFAULT_BATCH_SIZE = 24
        const val MAX_BATCH_SIZE = 64
        const val BASE_DELAY_MILLIS = 15_000L
        const val MAX_DELAY_MILLIS = 6 * 60 * 60 * 1000L
        const val MAX_SHIFT = 10
        const val MAX_ERROR_LENGTH = 512
    }
}
