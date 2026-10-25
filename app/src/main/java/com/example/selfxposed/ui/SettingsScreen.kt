package com.example.selfxposed.ui

import android.app.AlertDialog
import android.widget.LinearLayout

/** 设置页：日志入口、说明、弹窗样式与模块信息。 */
class SettingsScreen(private val host: ScreenHost) {
    private val ui get() = host.ui
    private val repo get() = host.repo

    fun render(into: LinearLayout) {
        into.addView(ui.sectionTitle("设置"))
        into.addView(ui.actionCard("实时日志", "查看某个目标应用的运行记录") { pickLogs() })
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
                    "目标应用：$targets 个\n\n" +
                    "仅对已添加并从 LSPosed 作用域勾选的应用生效。"
            )
            .setPositiveButton("确定", null)
            .show()
    }
}
