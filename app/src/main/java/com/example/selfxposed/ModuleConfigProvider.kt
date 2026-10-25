package com.example.selfxposed

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import org.json.JSONObject

class ModuleConfigProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val out = Bundle()
        val ctx = context ?: return out
        val pkg = arg?.takeIf { it.isNotBlank() } ?: return out
        val prefs = ctx.getSharedPreferences("module_config", 0)
        when (method) {
            "get_config" -> {
                val defaults = JSONObject()
                    .put("enabled", false)
                    .put("popupEnabled", true)
                    .put("popupText", "你好")
                    .put("showMs", 5000)
                    .put("fadeMs", 1000)
                val stored = try { JSONObject(prefs.getString("app:$pkg", "{}") ?: "{}") } catch (_: Throwable) { JSONObject() }
                val merged = JSONObject(defaults.toString())
                val keys = stored.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    merged.put(key, stored.get(key))
                }
                out.putString("config", merged.toString())
            }
            "set_config" -> {
                val json = extras?.getString("config") ?: "{}"
                prefs.edit().putString("app:$pkg", json).apply()
                out.putBoolean("ok", true)
            }
            "record_hook" -> {
                prefs.edit().putLong("hook_seen:$pkg", System.currentTimeMillis()).apply()
                out.putBoolean("ok", true)
            }
            "get_hook_status" -> {
                val seen = prefs.getLong("hook_seen:$pkg", 0L)
                out.putLong("last_seen", seen)
                out.putBoolean("detected", seen > 0L)
            }
            "append_log" -> {
                val line = extras?.getString("line") ?: return out
                synchronized(ModuleConfigProvider::class.java) {
                    val old = prefs.getString("logs:$pkg", "") ?: ""
                    val lines = (old.lines().filter { it.isNotBlank() } + line).takeLast(300)
                    prefs.edit().putString("logs:$pkg", lines.joinToString("\n")).apply()
                }
                out.putBoolean("ok", true)
            }
            "get_logs" -> out.putString("logs", prefs.getString("logs:$pkg", "") ?: "")
            "clear_logs" -> { prefs.edit().remove("logs:$pkg").apply(); out.putBoolean("ok", true) }
        }
        return out
    }
}
