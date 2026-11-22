package com.example.selfxposed

import android.content.Context
import java.io.File

/**
 * 工作模式：Root / Shizuku / 非 Root。
 *
 * 直接读写配置文件，不走 SharedPreferences（避免进程内缓存导致切换后读到旧值）。
 */
object WorkMode {
    const val ROOT = "root"
    const val SHIZUKU = "shizuku"
    const val NO_ROOT = "nofoot"
    private const val FILE_NAME = "selfxposed_work.conf"
    private const val KEY = "work_mode"

    private fun fileOf(context: Context) = File(context.filesDir, FILE_NAME)

    private const val CACHE_TTL_MS = 5_000L

    private data class Snapshot(val at: Long, val mode: String?)

    @Volatile
    private var snapshot: Snapshot? = null

    /** 工作模式 5 秒缓存：界面渲染里多处调用，不能每次都读盘解析。 */
    fun get(context: Context): String? {
        val now = System.currentTimeMillis()
        snapshot?.let { if (now - it.at < CACHE_TTL_MS) return it.mode }
        val mode = read(context)
        snapshot = Snapshot(now, mode)
        return mode
    }

    private fun read(context: Context): String? = try {
        val file = fileOf(context)
        if (!file.exists()) {
            null
        } else {
            file.readLines()
                .mapNotNull { line ->
                    val i = line.indexOf('=')
                    if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
                }
                .toMap()[KEY]
                ?.takeIf { it == ROOT || it == SHIZUKU || it == NO_ROOT }
        }
    } catch (_: Throwable) {
        null
    }

    fun set(context: Context, mode: String) {
        try {
            val file = fileOf(context)
            file.writeText("$KEY=$mode\n")
            file.setReadable(true, true)
            file.setWritable(true, true)
            snapshot = Snapshot(System.currentTimeMillis(), mode)
        } catch (_: Throwable) {
        }
    }

    /** 当前模式是否具备 root 能力（用于决定功能是否可点）。 */
    fun canUseRoot(context: Context): Boolean = when (get(context)) {
        ROOT -> true
        SHIZUKU -> ShizukuBridge.hasPermission()
        else -> false
    }

    fun label(mode: String?): String = when (mode) {
        ROOT -> "Root 模式"
        SHIZUKU -> "Shizuku 模式"
        NO_ROOT -> "非 Root 模式"
        else -> "未选择"
    }
}
