package com.example.selfxposed

import android.content.Context
import java.io.File

/**
 * 按功能的启用开关。
 *
 * 每个功能对每个目标应用独立开关，默认全部关闭。
 * 状态写在该功能自己的入口页面，存在目标应用本地的 files/selfxposed_features.conf，
 * 形如 popup=1、block=0。hook 在目标进程里直接读文件；模块 UI 通过 root/Shizuku 读写。
 */
object Features {
    private const val FILE = "selfxposed_features.conf"

    /** 该目标应用的模块总开关：关闭时所有功能都不工作。 */
    const val MASTER = "master"

    const val POPUP = "popup"
    const val BLOCK = "block"
    const val TOAST = "toast"

    private fun fileOf(context: Context) = File(context.filesDir, FILE)

    private fun parse(text: String): Map<String, Boolean> = text.lines()
        .mapNotNull { line ->
            val index = line.indexOf('=')
            if (index <= 0) null else line.substring(0, index).trim() to line.substring(index + 1).trim()
        }
        .filter { it.first.isNotEmpty() }
        .associate { it.first to (it.second == "1") }

    fun isOn(context: Context, feature: String): Boolean = try {
        val file = fileOf(context)
        if (!file.exists()) false else parse(file.readText())[feature] == true
    } catch (_: Throwable) {
        false
    }

    fun readRemote(pkg: String): Map<String, Boolean> = try {
        val text = RootShell.exec("cat /data/data/" + pkg + "/files/" + FILE + " 2>/dev/null")
        if (text.isBlank()) emptyMap() else parse(text)
    } catch (_: Throwable) {
        emptyMap()
    }

    fun setRemote(pkg: String, feature: String, on: Boolean): Boolean = try {
        val current = readRemote(pkg).toMutableMap()
        current[feature] = on
        val content = current.entries.joinToString("\n") { it.key + "=" + (if (it.value) 1 else 0) }
        val escaped = content.replace("\\", "\\\\").replace("'", "'\\''")
        val out = RootShell.exec(
            "mkdir -p /data/data/" + pkg + "/files; printf '%s\\n' '" + escaped + "' > /data/data/" + pkg + "/files/" + FILE + "; " +
                "chown $(stat -c %u:%g /data/data/" + pkg + ") /data/data/" + pkg + "/files/" + FILE + "; " +
                "chmod 660 /data/data/" + pkg + "/files/" + FILE + "; echo ok"
        )
        val ok = out.contains("ok")
        ok
    } catch (_: Throwable) {
        false
    }
}
