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

    const val BLOCK = "block"
    const val TOAST = "toast"
    /** 版本伪装（拦截强制更新） */
    const val UPDATE = "update"
    /** 去广告（开屏 / 摇一摇 / 弹窗 / 局部） */
    const val AD = "ad"
    /** 净化：点选隐藏 / 改写界面上的广告位、横幅、牛皮癣按钮 */
    const val PURIFY = "purify"
    /** 翻译：按内置词库把英文界面换成中文（纯离线，不联网） */
    const val TRANSLATE = "translate"
    /** 去广告调试：把目标应用的 DNS 请求域名写进日志，方便找广告域名 */
    const val AD_LOG = "adLog"

    private fun fileOf(context: Context) = File(context.filesDir, FILE)

    private const val CACHE_TTL_MS = 1_500L

    private data class Snapshot(
        val at: Long,
        val modified: Long,
        val length: Long,
        val values: Map<String, Boolean>
    )

    /**
     * 按文件路径缓存解析结果：hook 在目标进程读目标应用自己的目录，模块界面读自己的目录，
     * 同一个进程里的两条路径各存一份，互不干扰。
     */
    private val snapshots = java.util.concurrent.ConcurrentHashMap<String, Snapshot>()

    private fun parse(text: String): Map<String, Boolean> = text.lines()
        .mapNotNull { line ->
            val index = line.indexOf('=')
            if (index <= 0) null else line.substring(0, index).trim() to line.substring(index + 1).trim()
        }
        .filter { it.first.isNotEmpty() }
        .associate { it.first to (it.second == "1") }

    /**
     * 读配置：TTL 内直接用内存结果；过期后先用文件时间戳/长度确认有没有改过，
     * 没改也只刷新时间戳，不重新读盘解析。hook 收不到模块 UI 的写入通知，只能靠这层判断。
     */
    private fun load(file: File): Map<String, Boolean> {
        val now = System.currentTimeMillis()
        val modified = file.lastModified()
        val length = file.length()
        val cached = snapshots[file.path]
        if (cached != null && cached.modified == modified && cached.length == length) {
            snapshots[file.path] = cached.copy(at = now)
            return cached.values
        }
        val values = try {
            if (file.exists()) parse(file.readText()) else emptyMap()
        } catch (_: Throwable) {
            emptyMap()
        }
        snapshots[file.path] = Snapshot(now, modified, length, values)
        return values
    }

    fun isOn(context: Context, feature: String): Boolean = try {
        val file = fileOf(context)
        snapshots[file.path]?.let { cached ->
            if (System.currentTimeMillis() - cached.at < CACHE_TTL_MS) return cached.values[feature] == true
        }
        load(file)[feature] == true
    } catch (_: Throwable) {
        false
    }

    /** 本进程刚写过配置时调用，让下一次读取重新读盘。 */
    fun invalidate() {
        snapshots.clear()
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
        if (ok) invalidate()
        ok
    } catch (_: Throwable) {
        false
    }
}
