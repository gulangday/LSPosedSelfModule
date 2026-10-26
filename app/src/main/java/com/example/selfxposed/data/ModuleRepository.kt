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
     * Hook 是否在该应用里跑过：true=已注入，false=未注入，null=无法判断（应用没在运行）。
     *
     * 判断依据：目标应用进程在运行，且 seen 标记是在这次启动之后写的。
     * 应用没在运行时无法知道它这次启动有没有被注入，所以返回 null（界面显示"待确认"），
     * 不能拿旧标记糊弄成"已连接"。
     */
    fun hookDetected(pkg: String): Boolean? = hookCache[pkg]

    private val hookCache = java.util.concurrent.ConcurrentHashMap<String, Boolean?>()
    private var lastHookRefresh = 0L

    /** 后台刷新 root 侧的 hook 状态；2 秒内不重复读，避免刷主页时反复拉起 su。 */
    fun refreshHookStatuses(pkgs: List<String>, onChanged: () -> Unit) {
        val now = System.currentTimeMillis()
        if (pkgs.isEmpty() || now - lastHookRefresh < 2_000L) return
        lastHookRefresh = now
        android.util.Log.i("SelfXposedRoot", "开始检测注入状态：$pkgs")
        Thread {
            var changed = false
            pkgs.forEach { pkg ->
                val detected = readSeenViaRoot(pkg)
                if (hookCache[pkg] != detected) {
                    hookCache[pkg] = detected
                    changed = true
                }
            }
            if (changed) onChanged()
        }.start()
    }

    /** seen 标记超过 7 天视为过期（仅用于"应用没在运行"时判断历史注入）。 */
    private val seenMaxAgeMs = 7L * 24 * 60 * 60 * 1000

    private fun readSeenViaRoot(pkg: String): Boolean? = try {
        run {
            // 同时拿：运行标记时间、进程是否在跑、进程已运行时间
            val cmd = "P=\$(pidof $pkg 2>/dev/null | awk '{print \$1}'); " +
                "S=\$(cat /data/data/$pkg/files/selfxposed_seen 2>/dev/null); " +
                "if [ -n \"\$P\" ]; then " +
                "E=\$(ps -o etime= -p \$P 2>/dev/null | tr -d ' '); " +
                "echo \"run=1 seen=\$S etime=\$E\"; " +
                "else echo \"run=0 seen=\$S\"; fi"
            val out = RootShell.exec(cmd)
            android.util.Log.i("SelfXposedRoot", "注入检测 $pkg → ${out.trim().replace('\n', ' ')}")
            val seenAt = Regex("seen=(\\d+)").find(out)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val running = out.contains("run=1")
            if (running) {
                // 进程在跑：seen 必须是在这次启动前后写的（留 10 秒容差，吸收命令延迟/时钟误差）
                val etimeText = Regex("etime=([0-9:\\-]+)").find(out)?.groupValues?.get(1).orEmpty()
                val etimes = parseEtime(etimeText)
                val startedAt = System.currentTimeMillis() - etimes * 1000L
                val injected = seenAt > 0L && seenAt >= startedAt - 10_000L
                android.util.Log.i(
                    "SelfXposedRoot",
                    "注入判定 $pkg → seen=$seenAt startedAt=$startedAt 结果=$injected"
                )
                injected
            } else {
                // 没在运行：无法知道这次启动有没有注入 → 返回 null（待确认），
                // 不能拿旧标记糊弄成"已连接"。
                null
            }
        }
    } catch (_: Throwable) {
        null
    }

    /** 解析 ps 的 etime 格式：DD-HH:MM:SS / HH:MM:SS / MM:SS → 秒。 */
    private fun parseEtime(text: String): Long {
        var t = text.trim()
        if (t.isEmpty()) return 0L
        var days = 0L
        if (t.contains("-")) {
            days = t.substringBefore("-").toLongOrNull() ?: 0L
            t = t.substringAfter("-")
        }
        val parts = t.split(":").mapNotNull { it.toLongOrNull() }
        val seconds = when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0]
            else -> 0L
        }
        return days * 86400 + seconds
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
