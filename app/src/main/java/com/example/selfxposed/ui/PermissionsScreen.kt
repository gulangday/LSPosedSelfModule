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

        // ---- 后台常驻：状态跟随「通知权限」，有权限即常驻；点击卡片跳系统电池设置 ----
        val notifyAllowed = hasNotificationPermission()
        // 自动对齐：有权限 → 打开常驻；权限被撤销 → 关闭常驻
        if (notifyAllowed && !KeepAlive.isEnabled(ui.activity)) {
            KeepAlive.setEnabled(ui.activity, true)
            KeepAliveService.start(ui.activity)
        } else if (!notifyAllowed && KeepAlive.isEnabled(ui.activity)) {
            KeepAlive.setEnabled(ui.activity, false)
            KeepAliveService.stop(ui.activity)
        }
        val keepChip = ui.chip(
            if (notifyAllowed) "已开启" else "未开启",
            if (notifyAllowed) Palette.success else Palette.muted,
            if (notifyAllowed) Palette.successSoft else Palette.surfaceMuted
        ).apply {
            isClickable = false
        }
        into.addView(ui.card().apply {
            addView(rowWith("后台常驻", keepChip))
            addView(ui.label(
                if (notifyAllowed) {
                    "已开启：服务已启动（保持一条常驻通知）"
                } else {
                    "未开启：点这里授予通知权限后会自动常驻"
                },
                11, Palette.muted, false
            ).apply { setPadding(0, ui.dp(4), 0, 0) })
            addView(ui.label(
                "系统限制未关闭：点卡片去电池设置里选择「无限制」，否则系统可能杀掉进程",
                11, Palette.warning, false
            ).apply { setPadding(0, ui.dp(4), 0, 0) })
            ui.pressable(this, {
                if (!hasNotificationPermission()) openNotificationSettings() else openBatterySettings()
            })
        })

        // ---- 自启动（建议开启，非必需）----
        into.addView(ui.card().apply {
            addView(ui.label("自启动", 15, Palette.ink, true))
            addView(ui.label("建议开启（非必需）", 12, Palette.accent, true).apply {
                setPadding(0, ui.dp(4), 0, 0)
            })
            addView(ui.label(
                "· 拦截规则存在目标应用本地，不开启也能正常拦截\n" +
                    "· 只有模块被系统杀掉后、目标应用要读模块配置（如弹窗文字）时，\n" +
                    "  才需要系统能把模块重新拉起来\n" +
                    "· 点这里去系统设置里允许自启动",
                11, Palette.muted, false
            ).apply { setPadding(0, ui.dp(6), 0, 0) })
            ui.pressable(this, { openAutoStart() })
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
                "点击卡片请求 root 权限，系统会弹出授权窗（需要 Root 模式）；" +
                    "Shizuku / 非 Root 模式不需要这一步",
                11, Palette.muted, false
            ).apply { setPadding(0, ui.dp(4), 0, 0) })
            ui.pressable(this, {
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

    /** MIUI / ColorOS 的自启动页没有公开 action，只能按常见组件名尝试，失败退回应用详情页。 */
    private fun openAutoStart() {
        val candidates = listOf(
            android.content.ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            ),
            android.content.ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.AutoStartManagementActivity"
            ),
            android.content.ComponentName(
                "com.oplus.safecenter",
                "com.oplus.safecenter.permission.startup.StartupAppListActivity"
            ),
            android.content.ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity"
            )
        )
        for (component in candidates) {
            try {
                ui.activity.startActivity(android.content.Intent().setComponent(component))
                return
            } catch (_: Throwable) {
            }
        }
        try {
            ui.activity.startActivity(
                android.content.Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:${ui.activity.packageName}")
                )
            )
        } catch (_: Throwable) {
            host.toast("无法打开系统设置，请手动允许自启动")
        }
    }
}
