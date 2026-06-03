package org.openwebdav.messenger.transport

/**
 * Injectable delay abstraction so tests assert back-off timing without real sleeps
 * (plan: "injectable clock/scheduler so tests assert timing without real sleeps").
 */
internal fun interface Delayer {
    /** Suspend for [millis]. The production impl uses `kotlinx.coroutines.delay`. */
    suspend fun delay(millis: Long)
}

/**
 * Exponential back-off policy for `429` and timeout retries
 * (`docs/protocol/webdav-layout.md` §6; plan scenarios *rate_limit_429_backs_off_and_retries*,
 * *timeout_backs_off_and_retries*).
 *
 * @param maxRetries number of retries after the initial attempt.
 * @param baseDelayMillis delay before the first retry; doubles each subsequent retry.
 * @param maxDelayMillis cap on a single delay.
 */
internal data class BackOffPolicy(
    val maxRetries: Int = 4,
    val baseDelayMillis: Long = 1_000L,
    val maxDelayMillis: Long = 30_000L,
) {
    /** Delay before the retry numbered [attempt] (1-based): base * 2^(attempt-1), capped. */
    fun delayForAttempt(attempt: Int): Long {
        require(attempt >= 1) { "attempt is 1-based; got $attempt" }
        val shift = (attempt - 1).coerceAtMost(MAX_SHIFT)
        val raw = baseDelayMillis shl shift
        return if (raw <= 0L || raw > maxDelayMillis) maxDelayMillis else raw
    }

    private companion object {
        /** Guard against `shl` overflow on a Long for pathological retry counts. */
        const val MAX_SHIFT = 32
    }
}
