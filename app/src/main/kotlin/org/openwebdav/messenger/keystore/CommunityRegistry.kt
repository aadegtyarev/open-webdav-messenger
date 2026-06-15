package org.openwebdav.messenger.keystore

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Lightweight registry of joined communities — just the index (id, name, chatId).
 * The sensitive config (URL, password) stays Keystore-wrapped per community.
 */
internal class CommunityRegistry(context: Context) {
    private val file = File(context.filesDir, "connconfig/communities.json")

    fun all(): List<Entry> {
        if (!file.exists()) return emptyList()
        val json = JSONArray(file.readText())
        return (0 until json.length()).map { i ->
            val o = json.getJSONObject(i)
            Entry(
                id = o.getString("id"),
                name = o.getString("name"),
                chatId = o.getString("chatId"),
            )
        }
    }

    fun add(entry: Entry) {
        val list = all().toMutableList()
        list.add(entry)
        write(list)
    }

    fun remove(id: String) {
        write(all().filter { it.id != id })
    }

    private fun write(list: List<Entry>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(JSONObject().apply {
                put("id", e.id)
                put("name", e.name)
                put("chatId", e.chatId)
            })
        }
        file.parentFile?.mkdirs()
        file.writeText(arr.toString(2))
    }

    data class Entry(
        val id: String,
        val name: String,
        val chatId: String,
    )
}
