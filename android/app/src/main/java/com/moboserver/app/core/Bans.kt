package com.moboserver.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class BanEntry(val xuid: String, val name: String)

/**
 * Reads and edits the ban list. The file is owned by the embedded server
 * (`bans.json` next to the world); this helper only exists so a ban can be
 * undone from the app, otherwise a banned friend could never come back.
 */
object Bans {
    private fun file(context: Context) = File(context.filesDir, "bans.json")

    fun list(context: Context): List<BanEntry> {
        val file = file(context)
        if (!file.isFile) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val xuid = item.optString("xuid")
                if (xuid.isBlank()) null else BanEntry(xuid, item.optString("name"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun remove(context: Context, xuid: String) {
        write(context, list(context).filterNot { it.xuid == xuid })
    }

    private fun write(context: Context, entries: List<BanEntry>) {
        val array = JSONArray()
        for (entry in entries) {
            array.put(JSONObject().put("xuid", entry.xuid).put("name", entry.name))
        }
        try {
            file(context).writeText(array.toString(2))
        } catch (e: Exception) {
            // the app can not fix a read-only file; the UI simply keeps showing it
        }
    }
}
