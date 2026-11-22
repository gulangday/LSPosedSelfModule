package com.example.selfxposed.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import com.example.selfxposed.KeepAlive
import com.example.selfxposed.KeepAliveService

/** 权限页：后台常驻开关与系统自启动入口。 */
class PermissionsScreen(private val host: ScreenHost) {
    private val ui get() = host.ui

    fun render(into: LinearLayout) {
        into.addView(ui.backRow("‹  设置") { host.openSettings() })
        into.addView(ui.sectionTitle("权限"))

        addRootCard(into)

        // ---- 后台常驻：状态只由用户开关控制，不再错误地跟随通知权限自动开启 ----
        var keepEnabled = KeepAlive.isEnabled(ui.activity)
        val keepChip = ui.chip(
            if (keepEnabled) "已开启" else "未开启",
            if (keepEnabled) Palette.success else Palette.muted,
            if (keepEnabled) Palette.successSoft else Palette.surfaceMuted
        ).apply { isClickable = false }
        val keepDescription = ui.label(
            if (keepEnabled) "已启用：模块会尝试通过前台服务保持后台运行" else "未启用：模块不会主动启动常驻服务",
            11, Palette.muted, false
        ).apply { setPadding(0, ui.dp(4), 0, 0) }
        val keepCard = ui.card().apply {
            addView(rowWith("后台常驻", keepChip))
            addView(keepDescription)
            addView(ui.label(
                "系统电池限制仍可能回收进程，可通过电池设置调整。",
                11, Palette.warning, false
            ).apply { setPadding(0, ui.dp(4), 0, 0) })
        }
        // 仅状态按钮响应点击，卡片标题和说明区域不触发开关。
        ui.pressable(keepChip, {
            if (!keepEnabled && !hasNotificationPermission()) {
                host.toast("请先允许通知权限，再开启后台常驻")
                openNotificationSettings()
                return@pressable
            }
            val next = !keepEnabled
            KeepAlive.setEnabled(ui.activity, next)
            if (next) KeepAliveService.start(ui.activity) else KeepAliveService.stop(ui.activity)
            keepEnabled = next
            keepChip.text = if (next) "已开启" else "未开启"
            keepChip.setTextColor(if (next) Palette.success else Palette.muted)
            keepChip.background = ui.rounded(if (next) Palette.successSoft else Palette.surfaceMuted, 10)
            keepDescription.text = if (next) "已启用：模块会尝试通过前台服务保持后台运行" else "未启用：模块不会主动启动常驻服务"
            repoLog("后台常驻已${if (next) "开启" else "关闭"}")
            host.toast(if (next) "后台常驻已开启" else "后台常驻已关闭")
        })
        into.addView(keepCard)

        // ---- 自启动（建议开启，非必需）----
        into.addView(ui.card().apply {
            addView(ui.label("自启动", 15, Palette.ink, true))
            addView(ui.label("建议开启（非必需）", 12, Palette.accent, true).apply {
                setPadding(0, ui.dp(4), 0, 0)
            })
            addView(ui.label(
                "· 拦截规则存在目标应用本地，不开启也能正常拦截\n" +
                    "· 只有模块被系统杀掉后、目标应用要读模块配置（如弹窗文字）时，\n" +
                    "  才需要系统能把模块重新拉起来",
                11, Palette.muted, false
            ).apply { setPadding(0, ui.dp(6), 0, 0) })
            addView(ui.label("点击此处直接打开系统自启动设置 →", 11, 0xFFFFB300.toInt(), true).apply {
                setPadding(0, ui.dp(8), 0, 0)
                ui.pressable(this, { openAutoStart() })
            })
        })
    }

    /** Root 授权：点击触发 su 请求，Magisk 会弹授权窗。 */
    private fun addRootCard(into: LinearLayout) {
        val chip = ui.chip("检测中…", Palette.muted, Palette.surfaceMuted)

        fun applyState(ok: Boolean) {
            chip.text = if (ok) "已授权" else "未授权"
            chip.setTextColor(if (ok) Palette.success else Palette.warning)
            chip.background = ui.rounded(if (ok) Palette.successSoft else Palette.warningSoft, 10)
        }

        val card = ui.card().apply {
            addView(rowWith("Root 授权", chip))
            addView(ui.label(
                "仅点击右侧按钮请求 root 权限；Shizuku / 非 Root 模式不需要这一步",
                11, Palette.muted, false
            ).apply { setPadding(0, ui.dp(4), 0, 0) })
            ui.pressable(chip, {
                host.toast("正在请求 root，请在弹窗里选择允许")
                Thread {
                    val ok = try {
                        // 每次都真实执行一次 su，不走缓存，已授权时也会重新拉取一次
                        com.example.selfxposed.RootShell.requestRootNow()
                    } catch (_: Throwable) {
                        false
                    }
                    ui.activity.runOnUiThread {
                        applyState(ok)
                        host.toast(if (ok) "已获得 root 权限" else "未获得 root 权限")
                    }
                }.start()
            })
        }
        into.addView(card)

        // 后台查一次当前状态（不阻塞页面）
        Thread {
            val ok = try {
                com.example.selfxposed.RootShell.hasRootCached()
            } catch (_: Throwable) {
                false
            }
            ui.activity.runOnUiThread { applyState(ok) }
        }.start()
    }


    private fun repoLog(message: String) {
        host.repo.logModule(message)
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ui.activity.checkSelfPermission(
            "android.permission.POST_NOTIFICATIONS"
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** 跳系统电池设置：让系统别把这个应用杀掉（各 ROM 页面不同，逐个尝试）。 */
    private fun openBatterySettings() {
        val candidates = listOf(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent("android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")
                .setData(android.net.Uri.parse("package:${ui.activity.packageName}")),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:${ui.activity.packageName}"))
        )
        for (intent in candidates) {
            try {
                ui.activity.startActivity(intent)
                return
            } catch (_: Throwable) {
            }
        }
        host.toast("无法打开电池设置")
    }

    private fun openNotificationSettings() {
        try {
            ui.activity.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ui.activity.packageName)
            )
        } catch (_: Throwable) {
            host.toast("无法打开通知设置")
        }
    }

    private fun rowWith(title: String, chip: android.view.View): LinearLayout =
        LinearLayout(ui.activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.label(title, 15, Palette.ink, true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(2) })
        }

    /** MIUI / ColorOS 等系统的自启动管理页没有统一公开入口。 */
    private fun openAutoStart() {
        val manufacturer = Build.MANUFACTURER.lowercase(java.util.Locale.ROOT)
        val miui = listOf(
            "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.miui.securitycenter/com.miui.permcenter.AutoStartManagementActivity"
        )
        val oppo = listOf(
            "com.coloros.safecenter/com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oplus.safecenter/com.oplus.safecenter.permission.startup.StartupAppListActivity"
        )
        val samsung = listOf(
            "com.samsung.android.lool/com.samsung.android.sm.ui.battery.BatteryActivity"
        )
        val candidates = when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> miui + oppo
            manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") -> oppo + miui
            manufacturer.contains("samsung") -> samsung + miui + oppo
            else -> miui + oppo + samsung
        }
        for (flattened in candidates) {
            try {
                val component = android.content.ComponentName.unflattenFromString(flattened) ?: continue
                val intent = Intent().setComponent(component)
                if (intent.resolveActivity(ui.activity.packageManager) != null) {
                    ui.activity.startActivity(intent)
                    return
                }
            } catch (_: Throwable) { }
        }
        // Android 没有统一的自启动设置入口；通用设备退回应用详情页。
        try {
            ui.activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:${ui.activity.packageName}"))
            )
        } catch (_: Throwable) {
            host.toast("无法打开系统自启动设置，请在系统设置中手动允许")
        }
    }
}
