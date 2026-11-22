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
        val states = if (canRoot) readStates(targets) else emptyMap()
        // 有目标还没读回来时先显示「读取中」，不要拿不完整的数据算成「未启用」
        val loading = canRoot && states.size < targets.size

        if (canRoot && targets.any { TargetFiles.failedRecently(it) && !TargetFiles.hasState(it) }) {
            into.addView(ui.hint("部分目标应用状态读取失败，请检查 Root / Shizuku 授权。"))
            into.addView(ui.actionCard("重试读取", "重新读取目标应用状态") {
                targets.forEach { TargetFiles.invalidate(it) }
                host.refresh()
            })
        }

        fun count(feature: String): Int =
            if (!canRoot) 0 else states.values.count { it.isOn(Features.MASTER) && it.isOn(feature) }

        fun statusText(feature: String): String {
            if (targets.isEmpty()) return "先添加目标应用"
            if (!canRoot) return "需要 Root / Shizuku 模式"
            if (loading) return "读取中…"
            val n = count(feature)
            return if (n > 0) "已启用 $n 个应用" else "未启用"
        }

        fun statusColor(feature: String): Int =
            if (!loading && count(feature) > 0) Palette.success else Palette.warning

        fun statusBackground(feature: String): Int =
            if (!loading && count(feature) > 0) Palette.successSoft else Palette.warningSoft

        into.addView(featureCard(
            title = "拦截弹窗",
            subtitle = "按关键字或已学规则拦掉烦人的弹窗（更新提示等）",
            status = statusText(Features.BLOCK),
            statusColor = statusColor(Features.BLOCK),
            statusBackground = statusBackground(Features.BLOCK)
        ) { pickTarget(Features.BLOCK) { pkg, name -> host.openUpdateBlock(pkg, name) } })

        into.addView(featureCard(
            title = "版本伪装",
            subtitle = "伪造更高的版本号，拦掉强制更新（不更新不让用）",
            status = statusText(Features.UPDATE),
            statusColor = statusColor(Features.UPDATE),
            statusBackground = statusBackground(Features.UPDATE)
        ) { pickTarget(Features.UPDATE) { pkg, name -> host.openVersionSpoof(pkg, name) } })

        into.addView(featureCard(
            title = "去广告",
            subtitle = "开屏 / 摇一摇 / 广告落地页，一个开关全拦",
            status = statusText(Features.AD),
            statusColor = statusColor(Features.AD),
            statusBackground = statusBackground(Features.AD)
        ) { pickTarget(Features.AD) { pkg, name -> host.openAdBlock(pkg, name) } })

        into.addView(featureCard(
            title = "净化",
            subtitle = "点选隐藏 / 改写界面上的广告位、横幅、牛皮癣按钮",
            status = statusText(Features.PURIFY),
            statusColor = statusColor(Features.PURIFY),
            statusBackground = statusBackground(Features.PURIFY)
        ) { pickTarget(Features.PURIFY) { pkg, name -> host.openPurify(pkg, name) } })

        into.addView(featureCard(
            title = "翻译",
            subtitle = "把界面上的英文按钮、菜单、提示换成中文（纯离线，不联网）",
            status = statusText(Features.TRANSLATE),
            statusColor = statusColor(Features.TRANSLATE),
            statusBackground = statusBackground(Features.TRANSLATE)
        ) { pickTarget(Features.TRANSLATE) { pkg, name -> host.openTranslate(pkg, name) } })
    }

    /**
     * 只用缓存算状态。缓存缺失时后台批量补读一次再刷新，
     * 不在主线程逐包跑 root 命令（5 张卡各算 3 次会把命令数放大到 15×N）。
     */
    private fun readStates(targets: List<String>): Map<String, TargetFiles.TargetState> {
        val cached = HashMap<String, TargetFiles.TargetState>()
        val missing = ArrayList<String>()
        targets.forEach { pkg ->
            val state = TargetFiles.peek(pkg)
            if (state == null) missing.add(pkg) else cached[pkg] = state
        }
        if (missing.isEmpty()) return cached

        val rendered = targets.map { TargetFiles.peek(it) }
        // 刚失败过的先不再重复发命令，等用户点「重试读取」或 5 秒后再试
        val toLoad = missing.filter { !TargetFiles.failedRecently(it) }
        if (toLoad.isEmpty()) return cached
        Thread {
            toLoad.forEach { pkg ->
                try {
                    TargetFiles.load(pkg, force = true)
                } catch (_: Throwable) {
                }
            }
            ui.activity.runOnUiThread {
                if (ui.activity.isFinishing || ui.activity.isDestroyed) return@runOnUiThread
                // 读到的东西和当前显示一致就不要再刷新，否则会来回重建页面
                if (targets.map { TargetFiles.peek(it) } != rendered) host.refresh()
            }
        }.start()
        return cached
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
            // 只读缓存：点击就弹窗的路径上不能等 root 命令
            val state = if (canRoot) TargetFiles.peek(pkg) else null
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
