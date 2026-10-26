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

        into.addView(featureCard(
            title = "版本伪装",
            subtitle = "伪造更高的版本号，拦掉强制更新（不更新不让用）",
            status = statusText(Features.UPDATE, targets, canRoot),
            statusColor = statusColor(Features.UPDATE, targets, canRoot),
            statusBackground = statusBackground(Features.UPDATE, targets, canRoot)
        ) { pickTarget(Features.UPDATE) { pkg, name -> host.openVersionSpoof(pkg, name) } })

        into.addView(featureCard(
            title = "去广告",
            subtitle = "开屏 / 摇一摇 / 广告落地页，一个开关全拦",
            status = statusText(Features.AD, targets, canRoot),
            statusColor = statusColor(Features.AD, targets, canRoot),
            statusBackground = statusBackground(Features.AD, targets, canRoot)
        ) { pickTarget(Features.AD) { pkg, name -> host.openAdBlock(pkg, name) } })

        // 启动早期 Shizuku 可能还没绑定好，读到空状态会显示"未启用"；
        // 后台补读一次，只有和当前显示不一致时才刷新（避免无限刷新）。
        if (canRoot && targets.isNotEmpty()) {
            val renderedPopup = enabledCount(Features.POPUP, targets, canRoot)
            val renderedBlock = enabledCount(Features.BLOCK, targets, canRoot)
            Thread {
                var freshPopup = 0
                var freshBlock = 0
                targets.forEach { pkg ->
                    val state = try {
                        TargetFiles.load(pkg, force = true)
                    } catch (_: Throwable) {
                        null
                    } ?: return@forEach
                    if (state.isOn(Features.MASTER)) {
                        if (state.isOn(Features.POPUP)) freshPopup++
                        if (state.isOn(Features.BLOCK)) freshBlock++
                    }
                }
                if (freshPopup != renderedPopup || freshBlock != renderedBlock) {
                    ui.activity.runOnUiThread { host.refresh() }
                }
            }.start()
        }
    }

    /**
     * 功能页卡片的状态 = 「主页已启用」且「该功能开关已开启」的应用数。
     * 主页没启用时该功能不可用，自然不计入。
     */
    private fun enabledCount(feature: String, targets: List<String>, canRoot: Boolean): Int {
        if (!canRoot || targets.isEmpty()) return 0
        return targets.count { pkg ->
            val state = TargetFiles.load(pkg)
            state.isOn(Features.MASTER) && state.isOn(feature)
        }
    }

    private fun statusText(feature: String, targets: List<String>, canRoot: Boolean): String {
        if (targets.isEmpty()) return "先添加目标应用"
        if (!canRoot) return "需要 Root / Shizuku 模式"
        val count = enabledCount(feature, targets, canRoot)
        return if (count > 0) "已启用 $count 个应用" else "未启用"
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
            val state = if (canRoot) TargetFiles.load(pkg) else null
            // 主页未启用时该功能不可用，显示未启用
            val on = state != null && state.isOn(Features.MASTER) && state.isOn(feature)
            Triple(repo.label(pkg), if (!canRoot) "未知" else if (on) "已启用" else "未启用", on)
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
