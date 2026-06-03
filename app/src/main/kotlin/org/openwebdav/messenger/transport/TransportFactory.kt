package org.openwebdav.messenger.transport

import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Builds the transport with the single shared [OkHttpClient] the whole app reuses
 * (one instance / one connection pool — stack-notes OkHttp guidance).
 *
 * The client is created once and held in [sharedClient]; every [create] call shares it.
 * Tests inject their own client + [Delayer] directly into [WebDavTransport] and do not use
 * this factory's real timeouts.
 */
internal object TransportFactory {
    /** The one OkHttpClient instance reused across all calls (stack-notes: "create a single … and reuse it"). */
    val sharedClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Production [Delayer] backed by `kotlinx.coroutines.delay`. */
    private val realDelayer = Delayer { millis -> delay(millis) }

    fun create(
        config: ConnectionConfig,
        backOff: BackOffPolicy = BackOffPolicy(),
    ): WebDavTransport =
        WebDavTransport(
            config = config,
            client = sharedClient,
            backOff = backOff,
            delayer = realDelayer,
        )
}
