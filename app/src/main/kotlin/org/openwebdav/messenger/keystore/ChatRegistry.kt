package org.openwebdav.messenger.keystore

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Stores the list of chats within a community. One chat per community is the "General" default.
 */
internal class ChatRegistry(private val context: Context) {
    private fun file(communityId: String) = File(context.filesDir, "connconfig/chats-$communityId.json")

    fun all(communityId: String): List<Entry> {
        val f = file(communityId)
        if (!f.exists()) return emptyList()
        val json = JSONArray(f.readText())
        return (0 until json.length()).map { i ->
            val o = json.getJSONObject(i)
            Entry(o.getString("id"), o.getString("name"), o.getString("kind"))
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

    private fun write(
        communityId: String,
        list: List<Entry>,
    ) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(JSONObject().apply {
                put("id", e.id)
                put("name", e.name)
                put("kind", e.kind)
            })
        }
        val f = file(communityId)
        f.parentFile?.mkdirs()
        f.writeText(arr.toString(2))
    }

    data class Entry(
        val id: String,
        val name: String,
        val kind: String = "general",
    )
}
