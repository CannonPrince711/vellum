package app.vellum.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class RecentFile(val uri: String, val name: String, val time: Long)

class RecentFiles(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("recent", Context.MODE_PRIVATE)

    fun load(): List<RecentFile> = try {
        val arr = JSONArray(prefs.getString("items", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            RecentFile(o.getString("uri"), o.getString("name"), o.getLong("time"))
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun add(uri: String, name: String): List<RecentFile> {
        val list = (listOf(RecentFile(uri, name, System.currentTimeMillis())) + load().filter { it.uri != uri }).take(40)
        save(list); return list
    }

    fun remove(uri: String): List<RecentFile> {
        val list = load().filter { it.uri != uri }
        save(list); return list
    }

    private fun save(list: List<RecentFile>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("uri", it.uri).put("name", it.name).put("time", it.time)) }
        prefs.edit().putString("items", arr.toString()).apply()
    }
}
