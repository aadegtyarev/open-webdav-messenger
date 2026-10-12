package org.openwebdav.messenger.keystore

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Stores the list of chats within a community. One chat per community is the "General" default.
 */
internal class ChatRegistry(private val context: Context) {
    internal var afterAddReadForTest: (() -> Unit)? = null
    internal var afterObservedReadForTest: (() -> Unit)? = null

    private fun file(communityId: String): File {
        AccountIdentifier.requireValid(communityId)
        return File(context.filesDir, "connconfig/chats-$communityId.json")
    }

    fun all(communityId: String): List<Entry> =
        synchronized(FILE_LOCK) {
            val f = file(communityId)
            if (!f.exists()) return@synchronized emptyList()
            val json = JSONArray(f.readText())
            (0 until json.length()).map { i ->
                val o = json.getJSONObject(i)
                val access = o.optString("access", ACCESS_UNKNOWN)
                require(access in ACCESS_VALUES) { "Invalid chat access metadata" }
                Entry(
                    o.getString("id"),
                    o.getString("name"),
                    o.getString("kind"),
                    access,
                    o.optBoolean("locallyCreatedPrivate", false),
                    o.optBoolean("nonSelfMemberObserved", false),
                )
            }
        }

    fun add(
        communityId: String,
        entry: Entry,
    ) = synchronized(FILE_LOCK) {
        val list = all(communityId).toMutableList()
        afterAddReadForTest?.invoke()
        list.add(entry)
        write(communityId, list)
    }

    fun replace(
        communityId: String,
        entries: List<Entry>,
    ) = synchronized(FILE_LOCK) {
        write(communityId, entries.distinctBy { it.id })
    }

    fun clear(communityId: String) =
        synchronized(FILE_LOCK) {
            StrictFileOperations.delete(file(communityId))
        }

    fun updateIf(
        communityId: String,
        transform: (List<Entry>) -> List<Entry>?,
    ): Boolean =
        synchronized(FILE_LOCK) {
            val current = all(communityId)
            val updated = transform(current) ?: return@synchronized false
            if (updated != current) write(communityId, updated.distinctBy { it.id })
            true
        }

    fun hasAny(): Boolean =
        synchronized(FILE_LOCK) {
            File(context.filesDir, "connconfig").listFiles()?.any { it.name.startsWith("chats-") } == true
        }

    private fun write(
        communityId: String,
        list: List<Entry>,
    ) {
        list.forEach { AccountIdentifier.requireValid(it.id) }
        val arr = JSONArray()
        for (e in list) {
            arr.put(
                JSONObject().apply {
                    put("id", e.id)
                    put("name", e.name)
                    require(e.access in ACCESS_VALUES) { "Invalid chat access metadata" }
                    put("kind", e.kind)
                    put("access", e.access)
                    put("locallyCreatedPrivate", e.locallyCreatedPrivate)
                    put("nonSelfMemberObserved", e.nonSelfMemberObserved)
                },
            )
        }
        val f = file(communityId)
        f.parentFile?.mkdirs()
        val temporary = File(f.parentFile, "${f.name}.tmp")
        try {
            temporary.writeText(arr.toString(2))
            StrictFileOperations.atomicReplace(temporary, f)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    data class Entry(
        val id: String,
        val name: String,
        val kind: String = "general",
        val access: String = ACCESS_UNKNOWN,
        val locallyCreatedPrivate: Boolean = false,
        val nonSelfMemberObserved: Boolean = false,
    )

    fun markNonSelfMemberObserved(
        communityId: String,
        chatId: String,
    ) {
        updateIf(communityId) { rows ->
            val row = rows.singleOrNull { it.id == chatId } ?: return@updateIf rows
            afterObservedReadForTest?.invoke()
            if (row.nonSelfMemberObserved) {
                rows
            } else {
                rows.map {
                    if (it.id == chatId) it.copy(nonSelfMemberObserved = true) else it
                }
            }
        }
    }

    private companion object {
        val FILE_LOCK = Any()
        const val ACCESS_PUBLIC = "public"
        const val ACCESS_PRIVATE = "private"
        const val ACCESS_UNKNOWN = "unknown"
        val ACCESS_VALUES = setOf(ACCESS_PUBLIC, ACCESS_PRIVATE, ACCESS_UNKNOWN)
    }
}
