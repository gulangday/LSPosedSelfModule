package com.example.selfxposed

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 模块应用与目标进程之间的唯一通信入口。
 *
 * 目标应用进程由 LSPosed 注入后，通过 ContentProvider 读取配置、写入日志；
 * 模块自身的界面也走同一套接口，因此两端的字段名只在这里定义一次。
 */
object ConfigClient {
    /** 必须与 app/build.gradle.kts 的 applicationId 保持一致。 */
    const val MODULE_PACKAGE = "com.example.selfxposed"

    /** 首次安装后默认展示的目标应用。 */
    const val DEFAULT_TARGET = "com.kugou.android.lite"

    private val URI: Uri = Uri.parse("content://$MODULE_PACKAGE.config")

    // Provider 方法名
    const val M_GET_CONFIG = "get_config"
    const val M_SET_CONFIG = "set_config"
    const val M_RECORD_HOOK = "record_hook"
    const val M_HOOK_STATUS = "get_hook_status"
    const val M_APPEND_LOG = "append_log"
    const val M_GET_LOGS = "get_logs"
    const val M_CLEAR_LOGS = "clear_logs"
    const val M_GET_TARGETS = "get_targets"
    const val M_ADD_TARGET = "add_target"
    const val M_REMOVE_TARGET = "remove_target"
    const val M_GET_HINT_STYLE = "get_hint_style"
    const val M_SET_HINT_STYLE = "set_hint_style"

    // 字段名
    const val K_CONFIG = "config"
    const val K_LINE = "line"
    const val K_LOGS = "logs"
    const val K_TARGETS = "targets"
    const val K_DETECTED = "detected"
    const val K_LAST_SEEN = "last_seen"
    const val K_HINT_STYLE = "hint_style"

    fun defaults(): JSONObject = JSONObject()
        .put("enabled", false)
        .put("popupEnabled", true)
        .put("popupText", "你好")
        .put("showMs", 5000)
        .put("fadeMs", 1000)

    /** 把存储的配置合并到默认值之上，保证旧配置缺少新字段时也有合理默认。 */
    fun merge(stored: JSONObject?): JSONObject {
        val merged = defaults()
        if (stored == null) return merged
        val keys = stored.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            merged.put(key, stored.get(key))
        }
        return merged
    }

    /** 配置在 SharedPreferences 中的键。 */
    fun keyConfig(pkg: String) = "app:$pkg"

    fun keyLogs(pkg: String) = "logs:$pkg"

    fun keyHookSeen(pkg: String) = "hook_seen:$pkg"

    /** Provider 使用的偏好文件名；目标进程用 XSharedPreferences 读同一个文件。 */
    const val PREFS_NAME = "module_config"

    fun call(resolver: ContentResolver, method: String, pkg: String, extras: Bundle? = null): Bundle =
        resolver.call(URI, method, pkg, extras) ?: Bundle()

    fun config(resolver: ContentResolver, pkg: String): JSONObject {
        val raw = call(resolver, M_GET_CONFIG, pkg).getString(K_CONFIG) ?: "{}"
        return try { JSONObject(raw) } catch (_: Throwable) { defaults() }
    }

    fun save(resolver: ContentResolver, pkg: String, config: JSONObject) {
        val extras = Bundle().apply { putString(K_CONFIG, config.toString()) }
        call(resolver, M_SET_CONFIG, pkg, extras)
    }

    fun log(resolver: ContentResolver, pkg: String, message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val extras = Bundle().apply { putString(K_LINE, "$time  $message") }
        call(resolver, M_APPEND_LOG, pkg, extras)
    }

    fun logs(resolver: ContentResolver, pkg: String): String =
        call(resolver, M_GET_LOGS, pkg).getString(K_LOGS) ?: ""

    fun clearLogs(resolver: ContentResolver, pkg: String) {
        call(resolver, M_CLEAR_LOGS, pkg)
    }

    fun hookDetected(resolver: ContentResolver, pkg: String): Boolean = try {
        call(resolver, M_HOOK_STATUS, pkg).getBoolean(K_DETECTED, false)
    } catch (_: Throwable) { false }

    fun targets(resolver: ContentResolver): List<String> {
        val raw = call(resolver, M_GET_TARGETS, "").getString(K_TARGETS) ?: "[]"
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
        } catch (_: Throwable) { emptyList() }
    }

    fun addTarget(resolver: ContentResolver, pkg: String) {
        call(resolver, M_ADD_TARGET, pkg)
    }

    fun removeTarget(resolver: ContentResolver, pkg: String) {
        call(resolver, M_REMOVE_TARGET, pkg)
    }

    fun hintStyle(resolver: ContentResolver): String? = try {
        call(resolver, M_GET_HINT_STYLE, "").getString(K_HINT_STYLE)?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }

    fun saveHintStyle(resolver: ContentResolver, json: String) {
        val extras = Bundle().apply { putString(K_HINT_STYLE, json) }
        call(resolver, M_SET_HINT_STYLE, "", extras)
    }
}
