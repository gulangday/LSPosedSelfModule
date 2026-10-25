package com.example.selfxposed.ui

import android.widget.LinearLayout

/** 功能页：按模块卡片列出增强功能，每个应用独立配置。 */
class FeaturesScreen(private val host: ScreenHost) {
    private val ui get() = host.ui
    private val repo get() = host.repo

    fun render(into: LinearLayout) {
        into.addView(ui.sectionTitle("功能"))
        into.addView(ui.hint("针对目标应用的增强功能，每个应用独立配置。"))

        val targets = repo.targets()

        val popupOn = targets.count { repo.config(it).optBoolean("enabled", false) }
        into.addView(featureCard(
            title = "弹窗",
            subtitle = "底部提示：文字内容、显示时长与淡出时长",
            status = if (popupOn > 0) "已启用 $popupOn 个应用" else "未启用",
            statusColor = if (popupOn > 0) Palette.success else Palette.warning,
            statusBackground = if (popupOn > 0) Palette.successSoft else Palette.warningSoft
        ) { pickTarget { pkg, name -> host.openApp(pkg, name) } })

        val updateOn = targets.count { pkg ->
            repo.config(pkg).optJSONObject("updateBlock")?.optBoolean("enabled") == true
        }
        into.addView(featureCard(
            title = "拦截弹窗",
            subtitle = "按关键字或已学规则拦掉烦人的弹窗（更新提示等）",
            status = if (updateOn > 0) "已启用 $updateOn 个应用" else "未启用",
            statusColor = if (updateOn > 0) Palette.success else Palette.warning,
            statusBackground = if (updateOn > 0) Palette.successSoft else Palette.warningSoft
        ) { pickTarget { pkg, name -> host.openUpdateBlock(pkg, name) } })
    }

    /** 只有一个目标应用就直接进，多个则弹列表选择。 */
    private fun pickTarget(open: (pkg: String, name: String) -> Unit) {
        val targets = repo.targets()
        if (targets.isEmpty()) {
            ui.dialog("还没有目标应用", "请先在主页添加目标应用。", confirmText = "知道了", cancelText = null)
            return
        }
        if (targets.size == 1) {
            open(targets.first(), repo.label(targets.first()))
            return
        }
        val names = targets.map { repo.label(it) }
        ui.listDialog(
            title = "选择目标应用",
            items = names,
            onPick = { which -> open(targets[which], names[which]) }
        )
    }

    private fun featureCard(
        title: String,
        subtitle: String,
        status: String,
        statusColor: Int,
        statusBackground: Int,
        onClick: () -> Unit
    ) = ui.card().apply {
        addView(ui.label(title, 16, Palette.ink, true))
        addView(ui.label(subtitle, 12, Palette.muted, false).apply {
            setPadding(0, ui.dp(6), 0, 0)
        })
        addView(ui.chip(status, statusColor, statusBackground))
        ui.pressable(this, onClick)
    }
}
