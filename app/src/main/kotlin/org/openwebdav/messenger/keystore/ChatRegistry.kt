package org.openwebdav.messenger.keystore

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Stores the list of chats within a community. One chat per community is the "General" default.
 */
internal class ChatRegistry(private val context: Context) {
    private fun file(communityId: String): File {
        AccountIdentifier.requireValid(communityId)
        return File(context.filesDir, "connconfig/chats-$communityId.json")
    }

    fun all(communityId: String): List<Entry> {
        val f = file(communityId)
        if (!f.exists()) return emptyList()
        val json = JSONArray(f.readText())
        return (0 until json.length()).map { i ->
            val o = json.getJSONObject(i)
            val access = o.optString("access", ACCESS_UNKNOWN)
            require(access in ACCESS_VALUES) { "Invalid chat access metadata" }
            Entry(o.getString("id"), o.getString("name"), o.getString("kind"), access)
        }
    }

    fun add(
        communityId: String,
        entry: Entry,
    ) {
        val list = all(communityId).toMutableList()
        list.add(entry)
        write(communityId, list)
    }

    fun replace(
        communityId: String,
        entries: List<Entry>,
    ) {
        write(communityId, entries.distinctBy { it.id })
    }

    fun clear(communityId: String) {
        StrictFileOperations.delete(file(communityId))
    }

    fun hasAny(): Boolean = File(context.filesDir, "connconfig").listFiles()?.any { it.name.startsWith("chats-") } == true

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
                },
            )
        }
        val f = file(communityId)
        f.parentFile?.mkdirs()
        f.writeText(arr.toString(2))
    }

    data class Entry(
        val id: String,
        val name: String,
        val kind: String = "general",
        val access: String = ACCESS_UNKNOWN,
    )

    private companion object {
        const val ACCESS_PUBLIC = "public"
        const val ACCESS_PRIVATE = "private"
        const val ACCESS_UNKNOWN = "unknown"
        val ACCESS_VALUES = setOf(ACCESS_PUBLIC, ACCESS_PRIVATE, ACCESS_UNKNOWN)
    }
}
