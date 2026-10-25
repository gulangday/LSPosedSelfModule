package com.example.selfxposed

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject
import java.util.Locale
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
        val loaded = readConfig(activity, pkg) ?: run {
            logOnce(failedLogged, pkg) { writeLog(activity, pkg, "读取配置失败：模块与本应用无法通信") }
            return
        }
        val cfg = loaded.cfg

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

        // 启动页（Splash / 欢迎页）会在 1~2 秒后被主界面替换。
        // 在它上面挂弹窗会因为窗口销毁而“闪一下再出现”，所以直接跳过，等真正的界面。
        if (isTransientActivity(activity)) {
            logFramework("自用模块: $pkg 跳过启动页 ${activity.javaClass.simpleName}")
            return
        }

        // 已经显示中的弹窗：跟随到新的 Activity，保留剩余时间，不重置计时。
        if (PopupController.isShowing(pkg)) {
            PopupController.show(activity, pkg, text, showMs, fadeMs)
            return
        }

        // 刚发生过 Activity 切换时，把待显示任务改挂到当前 Activity 上。
        pendingShows.remove(pkg)?.let { pendingHandler.removeCallbacks(it) }

        val now = SystemClock.uptimeMillis()
        val last = lastShownAt[pkg] ?: 0L
        // 冷却时间覆盖一次完整展示，避免快速切换 Activity 时连续重弹。
        val cooldown = maxOf(4_000L, showMs.toLong() + fadeMs.toLong() + 1_500L)
        if (now - last < cooldown) return

        lastShownAt[pkg] = now
        scheduleShow(activity, pkg, text, showMs, fadeMs)
    }

    /**
     * 延迟一小段时间再真正挂载弹窗：如果这期间界面又被替换（常见于启动页 → 主界面），
     * 新的 resume 会取消这次任务并重新计时，最终只会在稳定下来的界面上弹一次。
     */
    private fun scheduleShow(activity: Activity, pkg: String, text: String, showMs: Int, fadeMs: Int) {
        val runnable = Runnable {
            pendingShows.remove(pkg)
            if (activity.isFinishing || activity.isDestroyed) return@Runnable
            val attached = PopupController.show(activity, pkg, text, showMs, fadeMs)
            writeLog(
                activity, pkg,
                "弹窗 attached=$attached activity=${activity.javaClass.simpleName} showMs=$showMs fadeMs=$fadeMs"
            )
            logFramework("自用模块: $pkg 显示弹窗 attached=$attached showMs=$showMs fadeMs=$fadeMs")
            logOnce(detectedLogged, pkg) { writeLog(activity, pkg, "已在 $pkg 显示弹窗") }
        }
        pendingShows[pkg] = runnable
        pendingHandler.postDelayed(runnable, SETTLE_DELAY_MS)
    }

    private fun isTransientActivity(activity: Activity): Boolean {
        val name = activity.javaClass.simpleName.lowercase(Locale.ROOT)
        return TRANSIENT_HINTS.any { name.contains(it) }
    }

    private data class LoadedConfig(val cfg: JSONObject, val source: String)

    /**
     * 优先通过 LSPosed 的文件服务直接读模块的 SharedPreferences：
     * 这条路径不需要唤醒模块应用进程，因此国产 ROM 的“自启动”拦截不会影响弹窗。
     * 直读不可用（例如极旧的 LSPosed）时再退回 ContentProvider。
     */
    private fun readConfig(activity: Activity, pkg: String): LoadedConfig? {
        // 每次重新构造实例：如果进程启动早期文件服务还没就绪，
        // 第一次读到的会是空表，缓存下来就永远读不到了。
        val raw = try {
            XSharedPreferences(ConfigClient.MODULE_PACKAGE, ConfigClient.PREFS_NAME)
                .getString(ConfigClient.keyConfig(pkg), null)
        } catch (t: Throwable) {
            logFramework("自用模块: 直读配置失败，$pkg：${describe(t)}")
            null
        }

        if (!raw.isNullOrBlank()) {
            return try {
                val merged = ConfigClient.merge(JSONObject(raw))
                logFramework("自用模块: $pkg 配置来自模块偏好文件")
                saveLocalCache(activity, pkg, merged)
                LoadedConfig(merged, "file")
            } catch (t: Throwable) {
                logFramework("自用模块: 配置解析失败，$pkg：${describe(t)}")
                null
            }
        }

        // 2. ContentProvider（能读到就用最新的，并刷新本地缓存）
        try {
            val cfg = ConfigClient.config(activity.contentResolver, pkg)
            logFramework("自用模块: $pkg 配置来自 Provider")
            saveLocalCache(activity, pkg, cfg)
            return LoadedConfig(cfg, "provider")
        } catch (t: Throwable) {
            logFramework("自用模块: Provider 读取配置失败，$pkg：${describe(t)}")
        }

        // 3. 兜底：目标应用自己的 SharedPreferences（持久化，重启后仍有效）。
        //    模块进程被划掉/未授权自启动时，仍能用上一次同步到的配置弹窗。
        val cached = loadLocalCache(activity, pkg)
        if (cached != null) {
            logFramework("自用模块: $pkg 配置来自本地缓存")
            return LoadedConfig(cached, "cache")
        }
        return null
    }

    private fun saveLocalCache(activity: Activity, pkg: String, cfg: JSONObject) {
        try {
            activity.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
                .edit().putString("config:$pkg", cfg.toString()).commit()
        } catch (_: Throwable) {
        }
    }

    private fun loadLocalCache(activity: Activity, pkg: String): JSONObject? = try {
        val raw = activity.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
            .getString("config:$pkg", null)
        if (raw.isNullOrBlank()) null else ConfigClient.merge(JSONObject(raw))
    } catch (_: Throwable) {
        null
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
        private const val SETTLE_DELAY_MS = 400L
        /** 配置缓存在目标应用自己的 SharedPreferences 里，模块进程不可用时兜底。 */
        private const val CACHE_PREFS = "selfxposed_cache"
        private val TRANSIENT_HINTS =
            listOf("splash", "welcome", "guide", "loading", "launch", "advert", "startup")

        private val detectedLogged = ConcurrentHashMap.newKeySet<String>()
        private val disabledLogged = ConcurrentHashMap.newKeySet<String>()
        private val popupOffLogged = ConcurrentHashMap.newKeySet<String>()
        private val failedLogged = ConcurrentHashMap.newKeySet<String>()
        private val lastShownAt = ConcurrentHashMap<String, Long>()
        private val pendingShows = ConcurrentHashMap<String, Runnable>()

        // 注意：不能在类初始化时 new Handler —— LSPosed 加载模块时主线程 Looper 可能还没就绪。
        private val pendingHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    }
}
