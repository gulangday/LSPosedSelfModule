package com.example.selfxposed.data

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.example.selfxposed.ConfigClient
import com.example.selfxposed.RootShell
import org.json.JSONObject
import java.text.Collator
import java.util.Locale

/**
 * 界面侧的数据访问层：把 ConfigClient 的原始 Bundle、PackageManager 查询
 * 收敛成页面真正需要的类型，页面里不再出现 JSONBundle 拼装。
 */
class ModuleRepository(private val activity: Activity) {
    private val resolver get() = activity.contentResolver
    private val pm: PackageManager get() = activity.packageManager

    fun config(pkg: String): JSONObject = try {
        ConfigClient.config(resolver, pkg)
    } catch (_: Throwable) {
        ConfigClient.defaults()
    }

    fun saveConfig(pkg: String, config: JSONObject) {
        ConfigClient.save(resolver, pkg, config)
    }

    fun log(pkg: String, message: String) {
        try {
            ConfigClient.log(resolver, pkg, message)
        } catch (_: Throwable) {
        }
    }

    fun logs(pkg: String): String = try {
        ConfigClient.logs(resolver, pkg)
    } catch (_: Throwable) {
        ""
    }

    /** 模块自身的运行日志，与各目标应用的 Hook 日志分开存储。 */
    fun logModule(message: String) {
        try { ConfigClient.log(resolver, ConfigClient.MODULE_PACKAGE, message) } catch (_: Throwable) {}
    }

    fun moduleLogs(): String = logs(ConfigClient.MODULE_PACKAGE)

    fun clearModuleLogs() = clearLogs(ConfigClient.MODULE_PACKAGE)

    fun clearLogs(pkg: String) {
        try {
            ConfigClient.clearLogs(resolver, pkg)
        } catch (_: Throwable) {
        }
    }

    fun targets(): List<String> = try {
        ConfigClient.targets(resolver)
    } catch (_: Throwable) {
        emptyList()
    }

    fun addTarget(pkg: String) {
        ConfigClient.addTarget(resolver, pkg)
    }

    fun removeTarget(pkg: String) {
        ConfigClient.removeTarget(resolver, pkg)
    }

    /** 包信息聚合：一次 PackageManager 查询同时拿到名称、版本、图标，避免每张卡查 4 次。 */
    private data class AppInfo(
        val label: String,
        val version: String?,
        val installed: Boolean,
        val icon: Drawable?
    )

    private val infoCache = object : android.util.LruCache<String, AppInfo>(64) {}

    private fun info(pkg: String): AppInfo = infoCache.get(pkg) ?: run {
        val info = try {
            val pi = pm.getPackageInfo(pkg, 0)
            val appInfo = pi.applicationInfo
            AppInfo(
                label = appInfo?.loadLabel(pm)?.toString() ?: pkg,
                version = pi.versionName,
                installed = true,
                icon = try {
                    appInfo?.loadIcon(pm)
                } catch (_: Throwable) {
                    null
                }
            )
        } catch (_: Throwable) {
            AppInfo(pkg, null, false, null)
        }
        infoCache.put(pkg, info)
        info
    }

    fun isInstalled(pkg: String): Boolean = info(pkg).installed

    fun versionName(pkg: String): String? = info(pkg).version

    fun label(pkg: String): String = info(pkg).label

    fun icon(pkg: String): Drawable? = info(pkg).icon

    /** 所有带启动图标的应用（不含自身），按中文排序。列表扫全部应用，走 [preloadLaunchableApps] 在后台加载。 */
    fun launchableApps(): List<AppEntry> {
        launchable?.let { return it }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val resolved = pm.queryIntentActivities(intent, 0)
        val seen = LinkedHashMap<String, AppEntry>()
        resolved.forEach { info ->
            val pkg = info.activityInfo?.packageName ?: return@forEach
            if (pkg == activity.packageName || seen.containsKey(pkg)) return@forEach
            val name = try {
                info.loadLabel(pm)?.toString() ?: pkg
            } catch (_: Throwable) {
                pkg
            }
            seen[pkg] = AppEntry(pkg, name)
        }
        val collator = Collator.getInstance(Locale.CHINA)
        val list = seen.values.sortedWith(compareBy(collator) { it.label })
        launchable = list
        return list
    }

    /** 已缓存的启动器列表；还没读过返回 null。 */
    fun launchableAppsCached(): List<AppEntry>? = launchable

    /** 后台加载启动器列表，完成后在主线程回调，结束后再刷新页面。 */
    fun preloadLaunchableApps(onReady: () -> Unit) {
        if (launchable != null) {
            onReady()
            return
        }
        if (launchableLoading) return
        launchableLoading = true
        Thread {
            try {
                launchableApps()
            } catch (_: Throwable) {
            }
            launchableLoading = false
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) onReady()
            }
        }.start()
    }

    @Volatile
    private var launchable: List<AppEntry>? = null

    @Volatile
    private var launchableLoading = false
}

data class AppEntry(val pkg: String, val label: String)
