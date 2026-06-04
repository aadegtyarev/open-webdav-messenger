package org.openwebdav.messenger.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.openwebdav.messenger.protocol.ChatPaths

/**
 * Unit + interaction tests for conditional writes, `412`, `429` back-off, and timeout retry per
 * the plan Test plan and `docs/protocol/webdav-layout.md` §6.
 */
class WebDavConcurrencyTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val metaPath = "${ChatPaths.META}/roster.json"

    // plan: conditional_put_sends_if_match — PUT carries If-Match with the ETag from PROPFIND.
    @Test
    fun conditional_put_sends_if_match() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(204))
            TestSupport.newTransport(server).write(metaPath, byteArrayOf(1, 2, 3), ifMatch = "\"etag-7\"")

            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("\"etag-7\"", request.getHeader("If-Match"))
        }

    // plan: conditional_put_412_is_typed_conflict — a 412 becomes a typed Conflict, not an exception.
    @Test
    fun conditional_put_412_is_typed_conflict() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(412))
            val result =
                TestSupport.newTransport(server).write(metaPath, byteArrayOf(1), ifMatch = "\"stale\"")
            assertTrue(result is WebDavResult.Conflict)
        }

    // plan: rate_limit_429_backs_off_and_retries — 429 then 200; back-off asserted via injected delayer.
    @Test
    fun rate_limit_429_backs_off_and_retries() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(429))
            server.enqueue(MockResponse().setResponseCode(429))
            server.enqueue(MockResponse().setResponseCode(201))

            val delayer = RecordingDelayer()
            val result =
                TestSupport.newTransport(server, delayer = delayer).write("$metaPath.x", byteArrayOf(1))

            assertTrue(result is WebDavResult.Success)
            // Two 429s → two back-off waits, exponential: 1000ms then 2000ms.
            assertEquals(listOf(1_000L, 2_000L), delayer.delays)
        }

    // plan: timeout_backs_off_and_retries — a timeout then success retries with back-off.
    @Test
    fun timeout_backs_off_and_retries() =
        runTest {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.enqueue(MockResponse().setResponseCode(201))

            val delayer = RecordingDelayer()
            val result =
                TestSupport.newTransport(server, delayer = delayer).write("$metaPath.t", byteArrayOf(1))

            assertTrue(result is WebDavResult.Success)
            assertEquals(listOf(1_000L), delayer.delays) // one timeout → one back-off wait
        }

    // interaction: conflict_412_retry_path — a 412 yields a typed conflict the caller re-attempts.
    @Test
    fun conflict_412_retry_path() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(412)) // competing writer
            server.enqueue(MockResponse().setResponseCode(204).setHeader("ETag", "\"fresh\"")) // re-PROPFIND
            server.enqueue(MockResponse().setResponseCode(204)) // re-PUT succeeds

            val transport = TestSupport.newTransport(server)
            val first = transport.write(metaPath, byteArrayOf(1), ifMatch = "\"stale\"")
            assertTrue(first is WebDavResult.Conflict)

            // Caller's retry path: re-read fresh ETag, re-apply, re-PUT — succeeds.
            val retry = transport.write(metaPath, byteArrayOf(2), ifMatch = "\"fresh\"")
            assertTrue(retry is WebDavResult.Success)
        }

    // review finding 7: cancelling the coroutine mid-retry stops further attempts — the executor is
    // cancellation-cooperative, so no extra network call is issued after cancel.
    @Test
    fun cancellation_mid_retry_stops_further_attempts() =
        runTest {
            // First attempt: 429 (forces a back-off). A second attempt would consume this 201 —
            // it must NOT happen because we cancel during the back-off.
            server.enqueue(MockResponse().setResponseCode(429))
            server.enqueue(MockResponse().setResponseCode(201))

            lateinit var job: Job
            // The delayer cancels the running coroutine during the first back-off window, then
            // suspends forever — so the only way the coroutine resumes is via cancellation, which
            // the executor's ensureActive() must honor before issuing a second call.
            val cancellingDelayer =
                Delayer {
                    job.cancel()
                    kotlinx.coroutines.awaitCancellation()
                }

            val transport = TestSupport.newTransport(server, delayer = cancellingDelayer)
            val scope = CoroutineScope(Dispatchers.Default + Job())
            // Start lazily so `job` is assigned before the coroutine body (and thus the delayer that
            // reads `job`) can run — otherwise the Dispatchers.Default thread can reach the delayer
            // before the main thread finishes the assignment, racing into UninitializedPropertyAccess.
            job =
                scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    transport.write("$metaPath.cancel", byteArrayOf(1))
                }
            job.start()
            job.join()

            assertTrue(job.isCancelled)
            // Exactly one request was issued (the initial 429); the retry never fired after cancel.
            assertEquals(1, server.requestCount)
        }

    // review finding 10: a transient 5xx is retried with the same exponential back-off as 429.
    @Test
    fun transient_5xx_is_retried_with_back_off() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(MockResponse().setResponseCode(201))

            val delayer = RecordingDelayer()
            val result =
                TestSupport.newTransport(server, delayer = delayer).write("$metaPath.5xx", byteArrayOf(1))

            assertTrue(result is WebDavResult.Success)
            // Two 5xx → two back-off waits, same exponential schedule as 429.
            assertEquals(listOf(1_000L, 2_000L), delayer.delays)
        }

    // review finding 10: a persistent 5xx past the budget surfaces as a TransportError carrying the
    // code (not RateLimited, which is reserved for persistent 429).
    @Test
    fun persistent_5xx_becomes_transport_error_with_code() =
        runTest {
            repeat(5) { server.enqueue(MockResponse().setResponseCode(502)) }
            val result =
                TestSupport.newTransport(server, delayer = RecordingDelayer())
                    .write("$metaPath.502", byteArrayOf(1))
            assertTrue(result is WebDavResult.TransportError)
            assertEquals(502, (result as WebDavResult.TransportError).code)
        }

    // interaction: back_off_window_survives_mid_cycle_429 — a mid-cycle 429 backs off and the
    // cycle resumes to completion; later requests are unaffected.
    @Test
    fun back_off_window_survives_mid_cycle_429() =
        runTest {
            // Request A: list succeeds.
            server.enqueue(
                MockResponse().setResponseCode(207)
                    .setBody(TestSupport.multistatus(ChatPaths.inbox("bob", "c"), "m" to "e")),
            )
            // Request B: 429 then 200 — backs off mid-cycle.
            server.enqueue(MockResponse().setResponseCode(429))
            server.enqueue(MockResponse().setResponseCode(201))
            // Request C: delete still completes after the back-off.
            server.enqueue(MockResponse().setResponseCode(204))

            val delayer = RecordingDelayer()
            val transport = TestSupport.newTransport(server, delayer = delayer)

            assertTrue(transport.list(ChatPaths.inbox("bob", "c")) is WebDavResult.Success)
            assertTrue(transport.write("p", byteArrayOf(1)) is WebDavResult.Success)
            assertTrue(transport.delete("p") is WebDavResult.Success)
            assertEquals(listOf(1_000L), delayer.delays) // exactly one mid-cycle back-off
        }
}
