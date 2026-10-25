package com.example.selfxposed.ui

import android.widget.LinearLayout
import android.widget.Switch
import com.example.selfxposed.KeepAlive
import com.example.selfxposed.KeepAliveService

/** 权限页：后台常驻开关与系统自启动入口。 */
class PermissionsScreen(private val host: ScreenHost) {
    private val ui get() = host.ui

    fun render(into: LinearLayout) {
        into.addView(ui.backRow("‹  设置") { host.openSettings() })
        into.addView(ui.sectionTitle("权限"))

        val keepAlive = Switch(ui.activity).apply {
            text = "后台常驻"
            textSize = 14f
            isChecked = KeepAlive.isEnabled(ui.activity)
            setPadding(0, ui.dp(4), 0, ui.dp(4))
            setOnCheckedChangeListener { _, checked ->
                KeepAlive.setEnabled(ui.activity, checked)
                if (checked) KeepAliveService.start(ui.activity) else KeepAliveService.stop(ui.activity)
            }
        }
        into.addView(ui.card().apply { addView(keepAlive) })
        into.addView(ui.hint("常驻为可选项（默认关闭）：会常驻一条通知，提高被唤醒的成功率。"))

        into.addView(ui.entryRow("自启动", "允许系统在需要时唤醒本模块") { openAutoStart() })
        into.addView(ui.hint("必须开启。目标应用读取配置时会唤醒本模块，未授权则被系统拦截。"))
    }

    /** MIUI 的自启动页没有公开 action，只能按常见组件名尝试，失败再退回应用详情页。 */
    private fun openAutoStart() {
        val candidates = listOf(
            android.content.ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            ),
            android.content.ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.AutoStartManagementActivity"
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
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:${ui.activity.packageName}")
                )
            )
        } catch (_: Throwable) {
            host.toast("无法打开系统设置，请手动允许自启动")
        }
    }
}
