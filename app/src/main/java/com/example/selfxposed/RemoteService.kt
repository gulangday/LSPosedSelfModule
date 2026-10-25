package com.example.selfxposed

import java.util.concurrent.TimeUnit

/**
 * 由 Shizuku 启动的远程服务。
 *
 * 注意：Shizuku 的 UserService 要求类本身就是一个 Binder（继承 AIDL 的 Stub），
 * 不能写成 android.app.Service，否则启动时会被 ClassCastException 拒绝。
 * 它运行在 Shizuku 的进程里（shell 或 root 身份）。
 */
class RemoteService : IRemoteService.Stub() {
    override fun runCommand(command: String): String = try {
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor(20, TimeUnit.SECONDS)
        output
    } catch (t: Throwable) {
        "error: ${t.javaClass.simpleName}: ${t.message}"
    }
}
