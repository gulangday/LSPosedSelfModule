package com.example.selfxposed.ui

import android.widget.LinearLayout
import com.example.selfxposed.DialogRules
import com.example.selfxposed.ShizukuBridge
import com.example.selfxposed.WorkMode

/**
 * 弹窗规则管理：规则存在目标应用自己的数据里（绕开 targetSdk 35 的限制）。
 * 读取/清除需要 root 能力：Root 模式（直接 su）或 Shizuku 模式（Shizuku 以 root 启动）。
 */
class DialogRulesScreen(
    private val host: ScreenHost,
    private val onlyPackage: String? = null
) {
    private val ui get() = host.ui
    private val repo get() = host.repo

    fun render(into: LinearLayout) {
        into.addView(ui.backRow("‹  返回") {
            if (onlyPackage != null) host.openApp(onlyPackage, repo.label(onlyPackage)) else host.openFeatures()
        })
        into.addView(ui.sectionTitle(if (onlyPackage != null) "弹窗规则 · ${repo.label(onlyPackage)}" else "弹窗规则"))
        into.addView(ui.hint("规则是模块在目标应用里学习到的，存在目标应用自己的数据目录，需要 root 能力才能查看和清除。"))

        if (!WorkMode.canUseRoot(ui.activity)) {
            into.addView(ui.card().apply {
                addView(ui.label("当前模式没有 root 能力", 15, Palette.ink, true))
                addView(ui.label("到 设置 → 工作模式 选择 Root 或 Shizuku 模式后再来。", 12, Palette.muted, false).apply {
                    setPadding(0, ui.dp(6), 0, 0)
                })
            })
            into.addView(ui.actionCard("切换工作模式", "Root / Shizuku / 非 Root") { host.openSettings() })
            return
        }

        if (ShizukuBridge.isAlive() && !ShizukuBridge.isBound()) {
            ShizukuBridge.bind(android.content.ComponentName(ui.activity, com.example.selfxposed.RemoteService::class.java))
            into.addView(ui.card().apply {
                addView(ui.label("正在连接 Shizuku…", 15, Palette.ink, true))
                addView(ui.label("首次连接需要几秒，连上后会自动刷新。", 12, Palette.muted, false).apply {
                    setPadding(0, ui.dp(6), 0, 0)
                })
            })
            Thread {
                ShizukuBridge.ensureBound(10_000L)
                ui.activity.runOnUiThread { host.refresh() }
            }.start()
            return
        }

        val targets = onlyPackage?.let { listOf(it) } ?: repo.targets()
        if (targets.isEmpty()) {
            into.addView(ui.actionCard("还没有目标应用", "先回主页添加") { host.openHome() })
            return
        }

        targets.forEach { pkg ->
            val rule = DialogRules.read(pkg)
            val card = ui.card()
            card.addView(ui.label(repo.label(pkg), 15, Palette.ink, true))
            card.addView(ui.label(pkg, 10, Palette.muted, false).apply {
                setPadding(0, ui.dp(2), 0, 0)
            })
            if (rule == null) {
                card.addView(ui.chip("未设置（或读取失败）", Palette.muted, Palette.surfaceMuted))
            } else {
                card.addView(ui.chip(DialogRules.modeLabel(rule.mode), Palette.success, Palette.successSoft))
                if (rule.titles.isNotEmpty()) {
                    card.addView(ui.label("已记录标题：${rule.titles.joinToString("、")}", 11, Palette.muted, false).apply {
                        setPadding(0, ui.dp(6), 0, 0)
                    })
                }
                card.addView(ui.actionCard("清除规则", "下次弹窗会重新询问") {
                    if (DialogRules.clear(pkg)) host.refresh() else host.toast("清除失败，检查 root / Shizuku 授权")
                })
            }
            into.addView(card)
        }

        into.addView(ui.actionCard("刷新", "重新读取规则") {
            ShizukuBridge.bind(android.content.ComponentName(ui.activity, com.example.selfxposed.RemoteService::class.java))
            host.refresh()
        })
    }
}
