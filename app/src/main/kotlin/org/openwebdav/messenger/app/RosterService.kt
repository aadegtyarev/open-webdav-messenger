package org.openwebdav.messenger.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openwebdav.messenger.transport.WebDavResult
import org.openwebdav.messenger.transport.WebDavTransport

/**
 * Reads the community roster from `meta/roster.json` on the WebDAV disk.
 * The roster is a JSON array of member identifiers.
 */
internal class RosterService(
    private val transport: WebDavTransport,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun load(): List<String> = withContext(ioDispatcher) {
        when (val result = transport.read("meta/roster.json", "meta/roster.json")) {
            is WebDavResult.Success ->
                when (val r = result.value) {
                    is org.openwebdav.messenger.transport.ReadResult.Ready -> {
                        val json = String(r.blob, Charsets.UTF_8)
                        try {
                            org.json.JSONArray(json).let { arr ->
                                (0 until arr.length()).map { arr.getString(it) }
                            }
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                    else -> emptyList()
                }
            else -> emptyList()
        }
    }

    /** Register our member id in the roster (append if not present). */
    suspend fun addMyself(memberId: String) = withContext(ioDispatcher) {
        val current = load().toMutableList()
        if (memberId !in current) {
            current.add(memberId)
            val json = org.json.JSONArray(current).toString()
            transport.write("meta/roster.json", json.toByteArray(Charsets.UTF_8))
            transport.ensureCollection("meta")
        }
    }
}
