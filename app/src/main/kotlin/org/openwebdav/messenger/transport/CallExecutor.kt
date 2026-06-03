package org.openwebdav.messenger.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Runs an OkHttp call on [ioDispatcher] with exponential back-off on `429` and network
 * failures/timeouts (`docs/protocol/webdav-layout.md` §6).
 *
 * Response-body handling is centralised here so **every** path closes the body exactly once
 * (OkHttp guidance, stack-notes): the response is consumed inside `response.use { }`, and the
 * body is closed before any retry.
 *
 * @param client the single shared [OkHttpClient] (one instance reused for all calls — stack-notes).
 * @param backOff retry budget and delays.
 * @param delayer injectable delay so tests assert timing without real sleeps.
 * @param ioDispatcher blocking I/O dispatcher; defaults to [Dispatchers.IO] (Kotlin stack-notes).
 */
internal class CallExecutor(
    private val client: OkHttpClient,
    private val backOff: BackOffPolicy,
    private val delayer: Delayer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Execute [request], mapping the response via [onResponse] inside a closed-body scope.
     * Retries on `429` and on a network failure/timeout up to the back-off budget; a persistent
     * `429` maps to [WebDavResult.RateLimited], a persistent I/O failure to
     * [WebDavResult.TransportError].
     */
    suspend fun <T> execute(
        request: Request,
        onResponse: (Response) -> WebDavResult<T>,
    ): WebDavResult<T> = withContext(ioDispatcher) { retryLoop(request, onResponse) }

    /**
     * The retry loop, factored out so its `return` statements ARE its value — every path returns,
     * so there is no unreachable tail to suppress (review finding 11). Returns a Done result, or
     * the terminal RateLimited / TransportError once the back-off budget is spent.
     */
    private suspend fun <T> retryLoop(
        request: Request,
        onResponse: (Response) -> WebDavResult<T>,
    ): WebDavResult<T> {
        var attempt = 0
        var lastOutcome: Retryable = Retryable.RateLimit
        while (true) {
            // Cooperative cancellation: if the coroutine was cancelled (e.g. the poll cycle was
            // torn down), stop immediately rather than issuing another network call.
            coroutineContext.ensureActive()
            when (val step = attempt(request, onResponse)) {
                is Step.Done -> return step.result
                is Step.RateLimited -> lastOutcome = Retryable.RateLimit
                is Step.ServerError -> lastOutcome = Retryable.ServerError(step.code)
                is Step.Failed -> lastOutcome = Retryable.Failed(step.error)
            }
            if (attempt >= backOff.maxRetries) return terminal(lastOutcome)
            attempt++
            delayer.delay(backOff.delayForAttempt(attempt))
        }
    }

    private fun <T> attempt(
        request: Request,
        onResponse: (Response) -> WebDavResult<T>,
    ): Step<T> =
        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == HTTP_TOO_MANY_REQUESTS -> Step.RateLimited
                    // Transient 5xx (500/502/503/504) are backed off with the SAME exponential
                    // policy as 429 and timeouts — matches Yandex.Disk flakiness (review finding 10).
                    // On budget exhaustion they surface as a TransportError carrying the code.
                    response.code in RETRYABLE_SERVER_ERRORS -> Step.ServerError(response.code)
                    else -> Step.Done(onResponse(response))
                }
            }
        } catch (e: CancellationException) {
            // A cancelled OkHttp call surfaces as an IOException; CancellationException must never be
            // treated as a retryable failure — rethrow so cancellation propagates immediately.
            throw e
        } catch (e: IOException) {
            // OkHttp surfaces connection failures and timeouts as IOException — both are retryable.
            Step.Failed(e)
        }

    /** Map the last retryable outcome to its terminal [WebDavResult] once the budget is spent. */
    private fun terminal(outcome: Retryable): WebDavResult<Nothing> =
        when (outcome) {
            is Retryable.RateLimit -> WebDavResult.RateLimited
            is Retryable.ServerError ->
                WebDavResult.TransportError(code = outcome.code, message = "HTTP ${outcome.code}")
            is Retryable.Failed ->
                WebDavResult.TransportError(
                    code = null,
                    message = outcome.error.message ?: "transport error",
                    cause = outcome.error,
                )
        }

    /** One iteration's outcome inside the retry loop. */
    private sealed interface Step<out T> {
        data class Done<T>(val result: WebDavResult<T>) : Step<T>

        data object RateLimited : Step<Nothing>

        data class ServerError(val code: Int) : Step<Nothing>

        data class Failed(val error: Throwable) : Step<Nothing>
    }

    /** The last retryable outcome seen, used to pick the terminal result on budget exhaustion. */
    private sealed interface Retryable {
        data object RateLimit : Retryable

        data class ServerError(val code: Int) : Retryable

        data class Failed(val error: Throwable) : Retryable
    }

    private companion object {
        const val HTTP_TOO_MANY_REQUESTS = 429
        val RETRYABLE_SERVER_ERRORS = setOf(500, 502, 503, 504)
    }
}
