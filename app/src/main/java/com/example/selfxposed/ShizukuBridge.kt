package com.example.selfxposed

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku

/**
 * Shizuku 通道（UserService 方式）。
 *
 * 隐藏 root 的模块会把 su 从普通应用视图里摘掉；Shizuku 服务本身是 shell（或 root）身份，
 * 在它的进程里执行命令就不受这个限制：
 * 1. Shizuku 以 root 启动（uid=0）→ 直接拥有 root 能力；
 * 2. Shizuku 以 shell 启动（uid=2000）→ 在里面执行 su，请求来自 shell，能触发 Magisk 授权。
 */
object ShizukuBridge {
    const val REQUEST_CODE = 4210

    @Volatile
    private var remote: IRemoteService? = null
    @Volatile
    private var binding = false
    @Volatile
    private var savedComponent: ComponentName? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remote = IRemoteService.Stub.asInterface(service)
            binding = false
            android.util.Log.i("SelfXposedRoot", "shizuku service connected: $service")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            binding = false
            android.util.Log.i("SelfXposedRoot", "shizuku service disconnected")
        }
    }

    fun isAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun hasPermission(): Boolean = try {
        isAlive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /** 0 = Shizuku 以 root 启动；2000 = shell；-1 = 不可用。 */
    fun uid(): Int = try {
        if (isAlive()) Shizuku.getUid() else -1
    } catch (_: Throwable) {
        -1
    }

    fun requestPermission() {
        try {
            if (!hasPermission()) Shizuku.requestPermission(REQUEST_CODE)
        } catch (_: Throwable) {
        }
    }

    /** 绑定远程服务（异步），需要 context 提供组件名。 */
    fun bind(componentName: ComponentName) {
        savedComponent = componentName
        if (!hasPermission()) {
            android.util.Log.i("SelfXposedRoot", "shizuku bind 跳过：没有权限")
            return
        }
        if (remote != null || binding) return
        binding = true
        try {
            val args = Shizuku.UserServiceArgs(componentName)
                // daemon=true：复用同一个常驻服务进程。
                // daemon=false 会每次绑定都新建一个 remote 进程，反复打开模块会堆积上百个，
                // 吃光内存后系统开始杀别的应用（表现为"相册闪退"）。
                .daemon(true)
                .processNameSuffix("remote")
                .debuggable(false)
                .version(1)
            Shizuku.bindUserService(args, connection)
            android.util.Log.i("SelfXposedRoot", "shizuku bind 已发起（daemon 复用）")
        } catch (t: Throwable) {
            binding = false
            android.util.Log.i("SelfXposedRoot", "shizuku bind 失败：${t.javaClass.simpleName}: ${t.message}")
        }
    }

    @Volatile
    private var waitedOnce = false

    /** 确保已绑定；整个进程只在第一次真正等待，之后快速返回，避免每个命令卡 2 秒。 */
    fun ensureBound(timeoutMs: Long = 2500L): Boolean {
        if (remote != null) return true
        savedComponent?.let { bind(it) }
        if (waitedOnce) return remote != null
        waitedOnce = true
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (remote != null) return true
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                return remote != null
            }
        }
        android.util.Log.i("SelfXposedRoot", "shizuku 等待绑定超时（remote=${remote != null}）")
        return remote != null
    }

    /** 通过 Shizuku 执行命令；未绑定成功返回空串。 */
    fun exec(command: String, timeoutMs: Long = 10_000L): String {
        val service = remote ?: return ""
        return try {
            val output = service.runCommand(command)
            android.util.Log.i(
                "SelfXposedRoot",
                "shizuku(uid=${uid()}) $command → ${output.take(120).replace('\n', ' ')}"
            )
            output
        } catch (t: Throwable) {
            android.util.Log.i("SelfXposedRoot", "shizuku exec 失败：${t.javaClass.simpleName}: ${t.message}")
            ""
        }
    }

    fun isBound(): Boolean = remote != null
}
