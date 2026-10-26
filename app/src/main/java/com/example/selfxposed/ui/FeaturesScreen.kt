package com.example.selfxposed.ui

import android.widget.LinearLayout
import com.example.selfxposed.Features
import com.example.selfxposed.TargetFiles
import com.example.selfxposed.WorkMode

/** 功能页：按模块卡片列出增强功能，每个应用独立配置。 */
class FeaturesScreen(private val host: ScreenHost) {
    private val ui get() = host.ui
    private val repo get() = host.repo

    fun render(into: LinearLayout) {
        into.addView(ui.sectionTitle("功能"))
        into.addView(ui.hint("针对目标应用的增强功能，每个应用独立配置；功能默认关闭。"))

        val targets = repo.targets()
        val canRoot = WorkMode.canUseRoot(ui.activity)

        into.addView(featureCard(
            title = "弹窗",
            subtitle = "底部提示：文字内容、显示时长与淡出时长",
            status = statusText(Features.POPUP, targets, canRoot),
            statusColor = statusColor(Features.POPUP, targets, canRoot),
            statusBackground = statusBackground(Features.POPUP, targets, canRoot)
        ) { pickTarget(Features.POPUP) { pkg, name -> host.openApp(pkg, name) } })

        into.addView(featureCard(
            title = "拦截弹窗",
            subtitle = "按关键字或已学规则拦掉烦人的弹窗（更新提示等）",
            status = statusText(Features.BLOCK, targets, canRoot),
            statusColor = statusColor(Features.BLOCK, targets, canRoot),
            statusBackground = statusBackground(Features.BLOCK, targets, canRoot)
        ) { pickTarget(Features.BLOCK) { pkg, name -> host.openUpdateBlock(pkg, name) } })
    }

    private fun enabledCount(feature: String, targets: List<String>, canRoot: Boolean): Int {
        if (!canRoot || targets.isEmpty()) return 0
        return targets.count { pkg -> TargetFiles.load(pkg).isOn(feature) }
    }

    private fun statusText(feature: String, targets: List<String>, canRoot: Boolean): String {
        if (targets.isEmpty()) return "先添加目标应用"
        if (!canRoot) return "需要 Root / Shizuku 模式"
        val count = enabledCount(feature, targets, canRoot)
        return if (count > 0) "已开启 $count 个应用" else "未开启"
    }

    private fun statusColor(feature: String, targets: List<String>, canRoot: Boolean): Int {
        val count = enabledCount(feature, targets, canRoot)
        return if (count > 0) Palette.success else Palette.warning
    }

    private fun statusBackground(feature: String, targets: List<String>, canRoot: Boolean): Int {
        val count = enabledCount(feature, targets, canRoot)
        return if (count > 0) Palette.successSoft else Palette.warningSoft
    }

    /** 只有一个目标应用就直接进，多个则弹列表选择（列表里标出是否已开启）。 */
    private fun pickTarget(feature: String, open: (pkg: String, name: String) -> Unit) {
        val targets = repo.targets()
        if (targets.isEmpty()) {
            ui.dialog("还没有目标应用", "请先在主页添加目标应用。", confirmText = "知道了", cancelText = null)
            return
        }
        if (targets.size == 1) {
            open(targets.first(), repo.label(targets.first()))
            return
        }
        val canRoot = WorkMode.canUseRoot(ui.activity)
        val labels = targets.map { pkg ->
            val on = canRoot && TargetFiles.load(pkg).isOn(feature)
            Triple(repo.label(pkg), if (!canRoot) "未知" else if (on) "已开启" else "未开启", on)
        }
        ui.statusListDialog(
            title = "选择目标应用",
            items = labels,
            onPick = { which -> open(targets[which], repo.label(targets[which])) }
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
