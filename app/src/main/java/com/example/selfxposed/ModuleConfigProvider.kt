package com.example.selfxposed

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

/**
 * 模块配置的唯一存储点：SharedPreferences 按包名分键。
 *
 * 这里只做“读写 + 校验”，不做任何界面决策；目标进程与模块界面共用。
 * 由于 LSPosed 把代码注入目标应用的进程，Provider 必须 exported，
 * 因此所有入口都限制参数格式与体积，避免被任意应用写入超大数据。
 */
class ModuleConfigProvider : ContentProvider() {

    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val out = Bundle()
        val ctx = context ?: return out
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        when (method) {
            ConfigClient.M_GET_CONFIG -> {
                val pkg = sanitizePackage(arg) ?: return out
                out.putString(ConfigClient.K_CONFIG, mergedConfig(prefs, pkg).toString())
            }

            ConfigClient.M_SET_CONFIG -> {
                val pkg = sanitizePackage(arg) ?: return out
                val raw = extras?.getString(ConfigClient.K_CONFIG)?.take(MAX_CONFIG_CHARS) ?: return out
                val json = try { JSONObject(raw) } catch (_: Throwable) { return out }
                prefs.edit().putString(keyConfig(pkg), json.toString()).apply()
                out.putBoolean("ok", true)
            }

            ConfigClient.M_RECORD_HOOK -> {
                val pkg = sanitizePackage(arg) ?: return out
                prefs.edit().putLong(keyHookSeen(pkg), System.currentTimeMillis()).apply()
                out.putBoolean("ok", true)
            }

            ConfigClient.M_HOOK_STATUS -> {
                val pkg = sanitizePackage(arg) ?: return out
                val seen = prefs.getLong(keyHookSeen(pkg), 0L)
                out.putLong(ConfigClient.K_LAST_SEEN, seen)
                out.putBoolean(ConfigClient.K_DETECTED, seen > 0L)
            }

            ConfigClient.M_APPEND_LOG -> {
                val pkg = sanitizePackage(arg) ?: return out
                val line = extras?.getString(ConfigClient.K_LINE)?.take(MAX_LINE_CHARS) ?: return out
                synchronized(ModuleConfigProvider::class.java) {
                    val old = prefs.getString(keyLogs(pkg), "") ?: ""
                    val lines = (old.lines().filter { it.isNotBlank() } + line).takeLast(MAX_LOG_LINES)
                    prefs.edit().putString(keyLogs(pkg), lines.joinToString("\n")).apply()
                }
                out.putBoolean("ok", true)
            }

            ConfigClient.M_GET_LOGS -> {
                val pkg = sanitizePackage(arg) ?: return out
                out.putString(ConfigClient.K_LOGS, prefs.getString(keyLogs(pkg), "") ?: "")
            }

            ConfigClient.M_CLEAR_LOGS -> {
                val pkg = sanitizePackage(arg) ?: return out
                prefs.edit().remove(keyLogs(pkg)).apply()
                out.putBoolean("ok", true)
            }

            ConfigClient.M_GET_TARGETS -> {
                out.putString(ConfigClient.K_TARGETS, readTargets(prefs).toString())
            }

            ConfigClient.M_ADD_TARGET -> {
                val pkg = sanitizePackage(arg) ?: return out
                val targets = readTargets(prefs)
                if (!containsTarget(targets, pkg)) {
                    targets.put(pkg)
                    prefs.edit().putString(KEY_TARGETS, targets.toString()).apply()
                }
                out.putBoolean("ok", true)
            }

            ConfigClient.M_REMOVE_TARGET -> {
                val pkg = sanitizePackage(arg) ?: return out
                val targets = readTargets(prefs)
                val kept = JSONArray()
                for (i in 0 until targets.length()) {
                    val item = targets.optString(i)
                    if (item != pkg) kept.put(item)
                }
                // 移除目标时同时清掉它的配置，避免残留的 enabled=true 继续触发注入。
                prefs.edit()
                    .putString(KEY_TARGETS, kept.toString())
                    .remove(keyConfig(pkg))
                    .apply()
                out.putBoolean("ok", true)
            }
        }
        return out
    }

    private fun mergedConfig(prefs: android.content.SharedPreferences, pkg: String): JSONObject {
        val stored = try {
            JSONObject(prefs.getString(ConfigClient.keyConfig(pkg), "{}") ?: "{}")
        } catch (_: Throwable) {
            null
        }
        return ConfigClient.merge(stored)
    }

    private fun readTargets(prefs: android.content.SharedPreferences): JSONArray {
        val raw = prefs.getString(KEY_TARGETS, null)
            ?: return JSONArray().put(ConfigClient.DEFAULT_TARGET)
        return try {
            JSONArray(raw)
        } catch (_: Throwable) {
            JSONArray().put(ConfigClient.DEFAULT_TARGET)
        }
    }

    private fun containsTarget(targets: JSONArray, pkg: String): Boolean {
        for (i in 0 until targets.length()) {
            if (targets.optString(i) == pkg) return true
        }
        return false
    }

    private fun sanitizePackage(raw: String?): String? {
        val value = raw?.trim() ?: return null
        if (value.length > MAX_PACKAGE_CHARS) return null
        if (!PACKAGE_PATTERN.matches(value)) return null
        return value
    }

    private fun keyConfig(pkg: String) = "app:$pkg"
    private fun keyLogs(pkg: String) = "logs:$pkg"
    private fun keyHookSeen(pkg: String) = "hook_seen:$pkg"

    companion object {
        private const val PREFS_NAME = "module_config"
        private const val KEY_TARGETS = "targets"
        private const val MAX_CONFIG_CHARS = 16 * 1024
        private const val MAX_LINE_CHARS = 600
        private const val MAX_LOG_LINES = 300
        private const val MAX_PACKAGE_CHARS = 200
        private val PACKAGE_PATTERN = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")
    }
}
