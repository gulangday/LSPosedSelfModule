package com.example.selfxposed.ui

import android.app.AlertDialog
import android.widget.LinearLayout

/** 设置页：只放入口，具体内容进去看。 */
class SettingsScreen(private val host: ScreenHost) {
    private val ui get() = host.ui
    private val repo get() = host.repo

    fun render(into: LinearLayout) {
        into.addView(ui.sectionTitle("设置"))
        into.addView(ui.entryRow("权限", "后台常驻、自启动") { host.openPermissions() })
        into.addView(ui.entryRow("实时日志", "查看目标应用的运行记录") { pickLogs() })
        into.addView(ui.entryRow("关于", "版本、包名与项目地址") { host.openAbout() })
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
}
