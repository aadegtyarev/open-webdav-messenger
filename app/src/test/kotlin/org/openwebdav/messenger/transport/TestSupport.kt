package org.openwebdav.messenger.transport

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit

/**
 * Shared test fixtures. A [RecordingDelayer] captures back-off delays so timing is asserted
 * without real sleeps (plan: injectable clock/scheduler). [newTransport] wires a transport at a
 * [MockWebServer] base URL using ONE shared client so `single_okhttpclient_reused` can assert it.
 */
internal class RecordingDelayer : Delayer {
    val delays = mutableListOf<Long>()

    override suspend fun delay(millis: Long) {
        delays.add(millis)
    }
}

internal object TestSupport {
    const val CHAT_ROOT = "chat-root"

    /** One client instance reused across [newTransport] calls within a test (stack-notes). */
    val sharedTestClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // Short timeouts so timeout-retry tests fail fast rather than hanging the suite.
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .writeTimeout(1, TimeUnit.SECONDS)
            .build()
    }

    fun config(server: MockWebServer): ConnectionConfig =
        ConnectionConfig(
            baseUrl = server.url("/").toString().trimEnd('/'),
            username = "user",
            appPassword = "app-password",
            chatRoot = CHAT_ROOT,
        )

    fun newTransport(
        server: MockWebServer,
        delayer: Delayer = RecordingDelayer(),
        client: OkHttpClient = sharedTestClient,
        backOff: BackOffPolicy = BackOffPolicy(maxRetries = 3, baseDelayMillis = 1_000L, maxDelayMillis = 8_000L),
    ): WebDavTransport =
        WebDavTransport(
            config = config(server),
            client = client,
            backOff = backOff,
            delayer = delayer,
        )

    /** A minimal multistatus body for an inbox listing the collection + [files]. */
    fun multistatus(
        inboxPath: String,
        vararg files: Pair<String, String>,
    ): String {
        val members =
            files.joinToString("\n") { (name, etag) ->
                """
                <d:response>
                  <d:href>/$CHAT_ROOT/$inboxPath/$name</d:href>
                  <d:propstat><d:prop>
                    <d:getetag>"$etag"</d:getetag>
                    <d:getcontentlength>10</d:getcontentlength>
                    <d:resourcetype/>
                  </d:prop></d:propstat>
                </d:response>
                """.trimIndent()
            }
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/$CHAT_ROOT/$inboxPath/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
              </d:response>
              $members
            </d:multistatus>
            """.trimIndent()
    }
}
