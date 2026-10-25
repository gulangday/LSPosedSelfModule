package com.example.selfxposed

import android.app.Activity
import android.app.Instrumentation
import android.os.SystemClock
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 注入入口：不再限定单一目标包名。
 *
 * LSPosed 只会把模块注入到“作用域里勾选过”的应用，因此这里对任何被注入的包都安装 Hook；
 * 是否真正弹窗完全由每个应用自己的配置（enabled / popupEnabled）决定。
 * 未添加为目标应用、或从未打开过的应用，读到的配置就是默认的 enabled = false。
 */
class MainHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName ?: return
        if (!shouldHook(pkg)) return
        logFramework("自用模块: 正在注入 $pkg")
        try {
            XposedHelpers.findAndHookMethod(
                Instrumentation::class.java,
                "callActivityOnResume",
                Activity::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.args.firstOrNull() as? Activity ?: return
                        if (activity.packageName != pkg) return
                        if (activity.isFinishing || activity.isDestroyed) return
                        try {
                            onActivityResumed(activity, pkg)
                        } catch (t: Throwable) {
                            logFramework("自用模块: 处理 $pkg 失败：${describe(t)}")
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            logFramework("自用模块: 在 $pkg 安装 Hook 失败：${describe(t)}")
        }
    }

    private fun onActivityResumed(activity: Activity, pkg: String) {
        val resolver = activity.contentResolver

        // 1. 记录一次运行时握手，供模块首页显示“已连接”。
        try {
            ConfigClient.call(resolver, ConfigClient.M_RECORD_HOOK, pkg)
        } catch (t: Throwable) {
            logFramework("自用模块: 记录 Hook 状态失败，$pkg：${describe(t)}")
        }

        // 2. 读取该应用的配置；读取失败时只提示一次，避免刷屏。
        val cfg = readConfig(activity, pkg) ?: run {
            logOnce(failedLogged, pkg) { writeLog(activity, pkg, "读取配置失败：模块与本应用无法通信") }
            return
        }

        if (!cfg.optBoolean("enabled", false)) {
            logOnce(disabledLogged, pkg) { writeLog(activity, pkg, "Hook 已触发，但模块开关处于关闭状态") }
            logFramework("自用模块: $pkg 开关未启用")
            return
        }
        if (!cfg.optBoolean("popupEnabled", true)) {
            logOnce(popupOffLogged, pkg) { writeLog(activity, pkg, "模块已开启，但提示通知处于关闭状态") }
            logFramework("自用模块: $pkg 提示通知未启用")
            return
        }

        val text = cfg.optString("popupText", "你好").ifBlank { "你好" }
        val showMs = cfg.optInt("showMs", 5000)
        val fadeMs = cfg.optInt("fadeMs", 1000)

        val now = SystemClock.uptimeMillis()
        val last = lastShownAt[pkg] ?: 0L
        // 冷却时间覆盖一次完整展示，避免快速切换 Activity 时连续重弹。
        val cooldown = maxOf(4_000L, showMs.toLong() + fadeMs.toLong() + 1_500L)
        if (now - last < cooldown) {
            if (PopupController.isShowing(pkg)) {
                // 弹窗还在屏幕上，只是宿主 Activity 变了：迁移过去并保留剩余时间。
                PopupController.show(activity, pkg, text, showMs, fadeMs)
            }
            return
        }

        lastShownAt[pkg] = now
        val shown = PopupController.show(activity, pkg, text, showMs, fadeMs)
        logFramework("自用模块: $pkg 显示弹窗 attached=$shown showMs=$showMs fadeMs=$fadeMs")
        logOnce(detectedLogged, pkg) { writeLog(activity, pkg, "已在 $pkg 显示弹窗") }
        writeLog(activity, pkg, "弹窗文本：$text（显示 ${showMs}ms，淡出 ${fadeMs}ms）")
    }

    /**
     * 优先通过 LSPosed 的文件服务直接读模块的 SharedPreferences：
     * 这条路径不需要唤醒模块应用进程，因此国产 ROM 的“自启动”拦截不会影响弹窗。
     * 直读不可用（例如极旧的 LSPosed）时再退回 ContentProvider。
     */
    private fun readConfig(activity: Activity, pkg: String): JSONObject? {
        val raw = try {
            filePrefs?.let { prefs ->
                if (prefs.hasFileChanged()) prefs.reload()
                prefs.getString(ConfigClient.keyConfig(pkg), null)
            }
        } catch (t: Throwable) {
            logFramework("自用模块: 直读配置失败，$pkg：${describe(t)}")
            null
        }

        if (!raw.isNullOrBlank()) {
            return try {
                ConfigClient.merge(JSONObject(raw))
            } catch (t: Throwable) {
                logFramework("自用模块: 配置解析失败，$pkg：${describe(t)}")
                null
            }
        }

        return try {
            ConfigClient.config(activity.contentResolver, pkg)
        } catch (t: Throwable) {
            logFramework("自用模块: Provider 读取配置失败，$pkg：${describe(t)}")
            null
        }
    }

    private fun logOnce(registry: MutableSet<String>, pkg: String, block: () -> Unit) {
        if (registry.add(pkg)) block()
    }

    private fun writeLog(activity: Activity, pkg: String, message: String) {
        try {
            ConfigClient.log(activity.contentResolver, pkg, message)
        } catch (_: Throwable) {
        }
    }

    private fun logFramework(message: String) {
        try {
            XposedBridge.log(message)
        } catch (_: Throwable) {
        }
    }

    private fun describe(t: Throwable) = "${t.javaClass.simpleName}: ${t.message ?: "无详细信息"}"

    /** 每个目标进程只创建一次；LSPosed 会把它代理到守护进程读取模块目录。 */
    private val filePrefs: XSharedPreferences? by lazy {
        try {
            XSharedPreferences(ConfigClient.MODULE_PACKAGE, ConfigClient.PREFS_NAME)
        } catch (t: Throwable) {
            logFramework("自用模块: XSharedPreferences 不可用：${describe(t)}")
            null
        }
    }

    private fun shouldHook(pkg: String): Boolean = when {
        pkg == ConfigClient.MODULE_PACKAGE -> false
        pkg == "android" -> false
        pkg.startsWith("android.") -> false
        pkg == "com.android.systemui" -> false
        pkg == "org.lsposed.manager" -> false
        pkg == "de.robv.android.xposed.installer" -> false
        else -> true
    }

    companion object {
        private val detectedLogged = ConcurrentHashMap.newKeySet<String>()
        private val disabledLogged = ConcurrentHashMap.newKeySet<String>()
        private val popupOffLogged = ConcurrentHashMap.newKeySet<String>()
        private val failedLogged = ConcurrentHashMap.newKeySet<String>()
        private val lastShownAt = ConcurrentHashMap<String, Long>()
    }
}
