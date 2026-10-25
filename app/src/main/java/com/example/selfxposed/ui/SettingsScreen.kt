package com.example.selfxposed.ui

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.Switch
import com.example.selfxposed.KeepAlive
import com.example.selfxposed.KeepAliveService

/** 设置页：日志入口、后台常驻/自启动、说明、弹窗样式与模块信息。 */
class SettingsScreen(private val host: ScreenHost) {
    private val ui get() = host.ui
    private val repo get() = host.repo

    fun render(into: LinearLayout) {
        into.addView(ui.sectionTitle("设置"))
        into.addView(ui.actionCard("实时日志", "查看某个目标应用的运行记录") { pickLogs() })

        into.addView(ui.sectionTitle("后台与自启动"))
        val keepAliveSwitch = Switch(ui.activity).apply {
            text = "后台常驻"
            textSize = 14f
            isChecked = KeepAlive.isEnabled(ui.activity)
            setPadding(0, ui.dp(4), 0, ui.dp(4))
            setOnCheckedChangeListener { _, checked ->
                KeepAlive.setEnabled(ui.activity, checked)
                if (checked) KeepAliveService.start(ui.activity) else KeepAliveService.stop(ui.activity)
                host.toast(if (checked) "已开启后台常驻" else "已关闭后台常驻")
            }
        }
        into.addView(ui.card().apply { addView(keepAliveSwitch) })
        into.addView(ui.hint("目标应用读配置时会唤醒本模块，被系统杀掉后需要「自启动」权限。后台常驻为可选项（默认关闭），能提高唤醒成功率，但会常驻一条通知。"))
        into.addView(ui.actionCard("打开自启动设置", "允许系统在需要时启动本模块") { openAutoStartSettings() })

        into.addView(ui.sectionTitle("其他"))
        into.addView(ui.actionCard("使用说明", "作用域、开关与弹窗之间的关系") { host.openHelp() })
        into.addView(ui.actionCard("弹窗样式", "底部居中 · 白底黑字 · 显示与淡出时长可调") { showPopupInfo() })
        into.addView(ui.actionCard("模块信息", "自用模块 · LSPosed") { showModuleInfo() })
    }

    private fun pickLogs() {
        val targets = repo.targets()
        if (targets.isEmpty()) {
            host.toast("当前没有目标应用")
            return
        }
        val names = targets.map { repo.label(it) }.toTypedArray()
        AlertDialog.Builder(ui.activity)
            .setTitle("选择应用日志")
            .setItems(names) { _, which -> host.openLogs(targets[which], names[which]) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** MIUI 的自启动页没有公开 action，只能按常见组件名尝试，失败再退回应用详情页。 */
    private fun openAutoStartSettings() {
        val candidates = listOf(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            ),
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.AutoStartManagementActivity"
            )
        )
        for (component in candidates) {
            try {
                ui.activity.startActivity(Intent().setComponent(component))
                return
            } catch (_: Throwable) {
            }
        }
        try {
            ui.activity.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${ui.activity.packageName}")
                )
            )
        } catch (_: Throwable) {
            host.toast("无法打开系统设置，请手动到系统设置里允许自启动")
        }
    }

    private fun showPopupInfo() {
        AlertDialog.Builder(ui.activity)
            .setTitle("弹窗样式")
            .setMessage(
                "弹窗由模块自绘，直接挂在当前 Activity 上：\n\n" +
                    "· 位置：底部居中，距底部约 96dp\n" +
                    "· 颜色：白底黑字，圆角卡片\n" +
                    "· 显示时长：可在应用设置里调（默认 5 秒）\n" +
                    "· 淡出时长：可在应用设置里调（默认 1 秒，可设为无淡出）\n\n" +
                    "不使用系统 Toast，因此时长可控；同一应用内切换页面也不会重置计时。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun showModuleInfo() {
        val targets = repo.targets().size
        AlertDialog.Builder(ui.activity)
            .setTitle("模块信息")
            .setMessage(
                "自用模块\n" +
                    "版本：${host.versionName()}\n" +
                    "包名：${ui.activity.packageName}\n" +
                    "目标应用：$targets 个\n" +
                    "后台常驻：${if (KeepAlive.isEnabled(ui.activity)) "已开启" else "未开启"}\n\n" +
                    "仅对已添加并从 LSPosed 作用域勾选的应用生效。"
            )
            .setPositiveButton("确定", null)
            .show()
    }
}
