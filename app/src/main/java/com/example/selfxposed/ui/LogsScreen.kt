package com.example.selfxposed.ui

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout

/** 日志页：倒序显示最近 300 条记录。 */
class LogsScreen(
    private val host: ScreenHost,
    private val pkg: String,
    private val name: String
) {
    private val ui get() = host.ui
    private val repo get() = host.repo

    fun render(into: LinearLayout) {
        into.addView(ui.backRow("‹  返回") { host.backFromLogs() })
        into.addView(ui.sectionTitle("$name · 日志"))

        val raw = repo.logs(pkg)
        val body = raw.lines()
            .filter { it.isNotBlank() }
            .asReversed()
            .joinToString("\n")
            .ifBlank { "暂无日志。打开目标应用并触发模块功能后，记录会显示在这里。" }

        val text = ui.label(body, 12, Palette.ink, false).apply {
            typeface = Typeface.MONOSPACE
            gravity = Gravity.START
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
            setBackgroundColor(Color.TRANSPARENT)
        }
        into.addView(ui.card().apply {
            addView(text, LinearLayout.LayoutParams(-1, -2))
        })

        into.addView(ui.actionCard("刷新日志", "重新读取当前记录") { host.refresh() })
        into.addView(ui.actionCard("清空日志", "只清空当前应用的记录") {
            ui.dialog(
                title = "清空日志",
                message = "将删除「$name」的全部运行记录，不可恢复。",
                confirmText = "清空",
                cancelText = "取消",
                onConfirm = {
                    repo.clearLogs(pkg)
                    host.toast("已清空")
                    host.refresh()
                }
            )
        })
    }

    companion object {
        private const val PAGE_APP = "app"
    }
}
