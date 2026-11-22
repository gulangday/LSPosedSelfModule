package com.example.selfxposed

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 自定义拦截关键字：一行一个，存在目标应用自己的 files 目录（hook 直接读）。
 * 模块写入需要 root。
 */
object Keywords {
    private const val FILE_NAME = "selfxposed_keywords.conf"
    private const val CACHE_TTL_MS = 2_000L

    private data class Entry(val at: Long, val keywords: List<String>)

    @Volatile
    private var cache: Entry? = null

    /** 关键字缓存（2 秒）：Toast 守卫和弹窗评估都会频繁调用，不能每次都读盘。 */
    fun load(context: Context): List<String> {
        val now = System.currentTimeMillis()
        cache?.let { if (now - it.at < CACHE_TTL_MS) return it.keywords }
        val keywords = try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) {
                emptyList()
            } else {
                file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
            }
        } catch (_: Throwable) {
            emptyList()
        }
        cache = Entry(now, keywords)
        return keywords
    }

    fun read(pkg: String): List<String> = try {
        val out = RootShell.exec("cat /data/data/$pkg/files/$FILE_NAME 2>/dev/null")
        out.lines().map { it.trim() }.filter { it.isNotEmpty() }
    } catch (_: Throwable) {
        emptyList()
    }

    fun write(context: Context, pkg: String, keywords: List<String>): Boolean = try {
        val tmp = File(context.cacheDir, "keywords.conf")
        tmp.writeText(keywords.joinToString("\n"))
        val cmd = listOf(
            "mkdir -p /data/data/$pkg/files",
            "cp '${tmp.absolutePath}' /data/data/$pkg/files/$FILE_NAME",
            "chown \$(stat -c %u:%g /data/data/$pkg) /data/data/$pkg/files/$FILE_NAME",
            "chmod 660 /data/data/$pkg/files/$FILE_NAME"
        ).joinToString("; ")
        RootShell.exec(cmd).isNotEmpty() || true
    } catch (_: Throwable) {
        false
    }
}

/** 关键字模板：存在模块自己的 prefs，套用时用 root 写入目标应用。 */
object KeywordTemplates {
    private const val PREFS = "selfxposed_templates"
    private const val KEY = "keywords"

    fun list(context: Context): Map<String, List<String>> = try {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "{}") ?: "{}"
        val json = JSONObject(raw)
        json.keys().asSequence().associateWith { name ->
            val array = json.optJSONArray(name) ?: JSONArray()
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
        }
    } catch (_: Throwable) {
        emptyMap()
    }

    fun save(context: Context, name: String, keywords: List<String>) {
        try {
            val current = JSONObject(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "{}") ?: "{}"
            )
            current.put(name, JSONArray(keywords))
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, current.toString()).apply()
        } catch (_: Throwable) {
        }
    }

    fun remove(context: Context, name: String) {
        try {
            val current = JSONObject(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "{}") ?: "{}"
            )
            current.remove(name)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, current.toString()).apply()
        } catch (_: Throwable) {
        }
    }
}
