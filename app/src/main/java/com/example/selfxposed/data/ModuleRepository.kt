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

    /**
     * Hook 是否在该应用里跑过。
     *
     * 正常情况模块能收到握手记录；但 targetSdk 30+ 的应用受包可见性限制，
     * 根本调不到模块的 Provider，只能由 hook 在应用本地写一个运行标记，
     * 模块再用 root 去读。
     */
    fun hookDetected(pkg: String): Boolean {
        if (ConfigClient.hookDetected(resolver, pkg)) return true
        return hookSeenViaRoot(pkg)
    }

    private fun hookSeenViaRoot(pkg: String): Boolean = try {
        val info = pm.getPackageInfo(pkg, 0)
        if ((info.applicationInfo?.targetSdkVersion ?: 0) < 30) {
            false
        } else if (!RootShell.hasRootCached()) {
            false
        } else {
            val out = RootShell.exec("cat /data/data/$pkg/files/selfxposed_seen 2>/dev/null")
            (out.trim().toLongOrNull() ?: 0L) > 0L
        }
    } catch (_: Throwable) {
        false
    }

    fun removeTarget(pkg: String) {
        ConfigClient.removeTarget(resolver, pkg)
    }

    fun isInstalled(pkg: String): Boolean = try {
        pm.getPackageInfo(pkg, 0)
        true
    } catch (_: Throwable) {
        false
    }

    fun versionName(pkg: String): String? = try {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(pkg, 0).versionName
    } catch (_: Throwable) {
        null
    }

    fun label(pkg: String): String = try {
        @Suppress("DEPRECATION")
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Throwable) {
        pkg
    }

    fun icon(pkg: String): Drawable? = try {
        pm.getApplicationIcon(pkg)
    } catch (_: Throwable) {
        null
    }

    /** 所有带启动图标的应用（不含自身），按中文排序。 */
    fun launchableApps(): List<AppEntry> {
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
        return seen.values.sortedWith(compareBy(collator) { it.label })
    }
}

data class AppEntry(val pkg: String, val label: String)
