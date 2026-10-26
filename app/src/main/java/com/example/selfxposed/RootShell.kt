package com.example.selfxposed

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 执行 root 命令的通道，按优先级：
 * 1. Shizuku 以 root 启动（uid=0）→ 直接执行；
 * 2. 直接调用 su（部分设备/隐藏 root 模块会让应用看不到 su）；
 * 3. 通过 Shizuku（shell 身份）执行 su → 请求来自 shell，能正常触发 Magisk 授权。
 */
object RootShell {
    private const val TIMEOUT_MS = 8_000L
    private const val PROBE_TIMEOUT_MS = 60_000L

    private val candidates = arrayOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/debug_ramdisk/su",
        "/sbin/su",
        "/data/adb/magisk/su"
    )

    private var cached = false
    private var checkedAt = 0L
    private var diagnosed = false
    private var shizukuIsRoot: Boolean? = null

    fun suPath(): String? = candidates.firstOrNull { path ->
        try {
            File(path).exists()
        } catch (_: Throwable) {
            false
        }
    }

    fun suExists(): Boolean = suPath() != null || (ShizukuBridge.isAlive() && ShizukuBridge.hasPermission())

    private fun quoted(command: String) = "'" + command.replace("'", "'\\''") + "'"

    /** shell 报错被当成输出（例如找不到 su）时，视为失败。 */
    private fun isToolError(output: String): Boolean =
        output.startsWith("/system/bin/sh:") ||
            output.contains("inaccessible or not found") ||
            output.contains("Permission denied") && output.length < 120

    /** 直接调用 su（不经 Shizuku）。 */
    private fun execDirect(command: String, timeoutMs: Long): String {
        val attempts = mutableListOf(
            listOf("/system/bin/sh", "-c", "su -c ${quoted(command)}"),
            listOf("sh", "-c", "su -c ${quoted(command)}"),
            listOf("su", "-c", command)
        )
        candidates.forEach { path -> attempts += listOf(path, "-c", command) }
        for (argv in attempts) {
            try {
                val process = ProcessBuilder(argv).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (finished && output.isNotBlank() && !isToolError(output)) {
                    android.util.Log.i("SelfXposedRoot", "direct(${argv[0]}) out=${output.take(100).replace('\n', ' ')}")
                    return output
                }
                android.util.Log.i(
                    "SelfXposedRoot",
                    "direct 失败 argv=${argv.joinToString(" ")} finished=$finished out=${output.take(120).replace('\n', ' ')}"
                )
            } catch (t: Throwable) {
                android.util.Log.i(
                    "SelfXposedRoot",
                    "direct 异常 argv=${argv.joinToString(" ")} ${t.javaClass.simpleName}: ${t.message}"
                )
            }
        }
        return ""
    }

    fun exec(command: String, timeoutMs: Long = TIMEOUT_MS): String {
        if (!diagnosed) {
            diagnosed = true
            android.util.Log.i(
                "SelfXposedRoot",
                "shizuku alive=${ShizukuBridge.isAlive()} perm=${ShizukuBridge.hasPermission()} uid=${ShizukuBridge.uid()} bound=${ShizukuBridge.isBound()} su=${suPath()}"
            )
        }

        // Shizuku 已授权但还没绑定好时，先等一下
        if (!ShizukuBridge.isBound() && ShizukuBridge.hasPermission()) {
            ShizukuBridge.ensureBound(2000L)
        }

        // 1. 通过 Shizuku 的远程进程执行
        if (ShizukuBridge.isBound()) {
            val root = shizukuIsRoot ?: run {
                val isRoot = ShizukuBridge.exec("id").contains("uid=0")
                shizukuIsRoot = isRoot
                isRoot
            }
            if (root) {
                val output = ShizukuBridge.exec(command, timeoutMs)
                if (output.isNotBlank()) return output
            } else {
                // Shizuku 是 shell 身份：在里面执行 su，请求来自 shell，可触发 Magisk 授权
                val output = ShizukuBridge.exec("su -c ${quoted(command)}", PROBE_TIMEOUT_MS)
                if (output.isNotBlank() && !output.contains("Permission denied")) return output
            }
        }

        // 2. 直接 su
        val direct = execDirect(command, timeoutMs)
        if (direct.isNotBlank()) return direct

        // 3. Shizuku 已授权但还没绑定好时，等绑定后的下一次调用
        return ""
    }

    /** 请求/校验 root；会等待 Magisk 授权框（最多 60 秒）。 */
    fun hasRoot(): Boolean {
        // Shizuku 以 root 启动时直接算有 root 能力
        if (ShizukuBridge.isBound()) {
            val isRoot = shizukuIsRoot ?: ShizukuBridge.exec("id").contains("uid=0").also {
                shizukuIsRoot = it
            }
            if (isRoot) {
                cached = true
                checkedAt = System.currentTimeMillis()
                return true
            }
        }
        val granted = exec("id", PROBE_TIMEOUT_MS).contains("uid=0")
        cached = granted
        checkedAt = System.currentTimeMillis()
        return granted
    }

    /**
     * 主动请求 root：优先直接走 su（这样每次点都会真正拉起 Magisk 授权/记录一次），
     * 只有在 su 不可用时才退回 Shizuku 通道。
     */
    fun requestRootNow(): Boolean {
        val direct = execDirect("id", PROBE_TIMEOUT_MS)
        val output = if (direct.isNotBlank()) direct else exec("id", PROBE_TIMEOUT_MS)
        val granted = output.contains("uid=0")
        cached = granted
        checkedAt = System.currentTimeMillis()
        return granted
    }

    /** 缓存 60 秒，避免每次刷主页都跑一遍 root 探测。 */
    fun hasRootCached(): Boolean {
        val now = System.currentTimeMillis()
        if (now - checkedAt > 60_000L) {
            cached = hasRoot()
            checkedAt = System.currentTimeMillis()
        }
        return cached
    }
}
