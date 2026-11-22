package com.example.selfxposed.ui

import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.example.selfxposed.Features
import com.example.selfxposed.LsposedScope
import com.example.selfxposed.TargetFiles
import com.example.selfxposed.WorkMode
import java.util.Locale

/** 主页：模块控制台 + 目标应用卡片列表。 */
class HomeScreen(private val host: ScreenHost) {
    private val ui get() = host.ui
    private val repo get() = host.repo

    private data class Entry(
        val pkg: String,
        val label: String,
        val installed: Boolean,
        var enabled: Boolean,
        val version: String?,
        val seenAt: Long,
        val scope: Boolean?
    )

    fun render(into: LinearLayout) {
        into.addView(heroCard())
        // 操作入口固定在列表上方，应用多了也不会被挤到最下面。
        into.addView(ui.actionCard("添加目标应用", "从已安装的启动器应用中选择") { host.openPicker() })
        into.addView(ui.actionCard("使用说明", "作用域与模块开关需要同时启用") { host.openHelp() })

        into.addView(ui.sectionTitle("目标应用"))
        into.addView(ui.hint("开关状态与 LSPosed 作用域是两个独立状态：作用域没有勾选时模块不会被注入，开关也不会生效。"))

        val canRootHome = WorkMode.canUseRoot(ui.activity)
        val entries = repo.targets().map { pkg ->
            Entry(
                pkg = pkg,
                label = repo.label(pkg),
                installed = repo.isInstalled(pkg),
                // 首帧只用缓存，避免冷启动等 Shizuku 绑定；后台会补读并刷新
                enabled = canRootHome && TargetFiles.peek(pkg)?.isOn(Features.MASTER) == true,
                version = repo.versionName(pkg),
                seenAt = if (canRootHome) TargetFiles.peek(pkg)?.seenAt ?: 0L else 0L,
                scope = if (canRootHome) LsposedScope.cached(pkg) else null
            )
        }.sortedWith(
            compareByDescending<Entry> { it.installed }
                .thenByDescending { it.enabled }
                .thenBy { it.label.lowercase(Locale.ROOT) }
        )

        if (entries.isEmpty()) {
            into.addView(ui.card().apply {
                addView(ui.label("还没有目标应用", 15, Palette.ink, true))
                addView(ui.label("点击下方「添加目标应用」，从已安装应用里选择要弹窗的应用。", 12, Palette.muted, false).apply {
                    setPadding(0, ui.dp(6), 0, 0)
                })
            })
        } else {
            entries.forEach { into.addView(appCard(it, canRootHome)) }
        }

        // 后台补读状态：整轮只跑一条批量 shell + 一次作用域批量查询，
        // 逐包读取会把 root 调用放大成 N 倍。只在与当前显示不一致时刷新。
        if (canRootHome && entries.isNotEmpty()) {
            val rendered = entries.associate { it.pkg to it.enabled }
            val renderedSeen = entries.associate { it.pkg to (it.seenAt > 0L) }
            val renderedScope = entries.associate { it.pkg to it.scope }
            val pkgs = entries.map { it.pkg }
            Thread {
                val states = try {
                    TargetFiles.loadAll(pkgs, force = true)
                } catch (_: Throwable) {
                    emptyMap()
                }
                val scopes = try {
                    LsposedScope.isInScopeBatch(ui.activity, pkgs, force = true)
                } catch (_: Throwable) {
                    null
                }
                val changed = pkgs.any { pkg ->
                    val fresh = states[pkg] ?: return@any false
                    fresh.isOn(Features.MASTER) != rendered[pkg] ||
                        (fresh.seenAt > 0L) != renderedSeen[pkg] ||
                        (scopes != null && scopes[pkg] != renderedScope[pkg])
                }
                if (changed) {
                    ui.activity.runOnUiThread {
                        if (!ui.activity.isFinishing && !ui.activity.isDestroyed) host.refresh()
                    }
                }
            }.start()
        }
    }

    private fun heroCard(): View = ui.card(Palette.hero).apply {
        elevation = 0f
        addView(ui.label("模块控制台", 12, Palette.accent, true))
        addView(ui.label("让每项功能状态一目了然", 19, Palette.ink, true).apply {
            setPadding(0, ui.dp(6), 0, ui.dp(4))
        })
        addView(ui.label("先在 LSPosed 中启用模块并勾选目标作用域，再启动一次目标应用完成 Hook 检测。", 12, Palette.muted, false))
    }

    private fun appCard(entry: Entry, canRoot: Boolean): View {
        val card = ui.card(if (entry.installed) Palette.surface else Palette.surfaceMuted)
        if (!entry.installed) card.alpha = 0.72f

        val row = LinearLayout(ui.activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ui.dp(64)
        }

        // 左侧整块（图标、名称、包名、版本、状态、空白）长按可移除；点击无响应。
        val removeApp = { confirmRemove(entry); true }

        val left = LinearLayout(ui.activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ui.dp(6), ui.dp(8), ui.dp(6))
            isClickable = false
            isFocusable = false
            isLongClickable = true
            setOnLongClickListener { removeApp() }
        }

        val placeholder: Drawable =
            ui.activity.resources.getDrawable(android.R.drawable.sym_def_app_icon, null)
        val iconView = ui.icon(repo.icon(entry.pkg) ?: placeholder, 40).apply {
            alpha = if (entry.installed) 1f else 0.5f
            (layoutParams as LinearLayout.LayoutParams).rightMargin = ui.dp(10)
            isClickable = false
            isFocusable = false
        }
        left.addView(iconView)

        val details = LinearLayout(ui.activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = false
            isFocusable = false
        }

        fun plainText(text: String, sizeSp: Int, color: Int, bold: Boolean, topPad: Int) =
            ui.label(text, sizeSp, color, bold).apply {
                setPadding(0, ui.dp(topPad), 0, ui.dp(1))
                isClickable = false
                isFocusable = false
            }

        details.addView(
            plainText(entry.label, 15, if (entry.installed) Palette.ink else Palette.muted, true, 0),
            LinearLayout.LayoutParams(-2, -2)
        )
        details.addView(
            plainText(entry.pkg, 10, Palette.muted, false, 2),
            LinearLayout.LayoutParams(-2, -2)
        )
        details.addView(ui.label("版本：${entry.version ?: "未安装"}", 10, Palette.muted, false))

        val injectionText = when {
            !entry.enabled -> "注入：模块未启用"
            entry.scope == false -> "注入：作用域未勾选"
            entry.seenAt > 0L && entry.scope == true -> "注入：已检测"
            entry.seenAt > 0L -> "注入：已检测（作用域未确认）"
            else -> "注入：待检测 · 点此检查"
        }
        val injectionColor = when {
            !entry.enabled -> Palette.muted
            entry.scope == false -> Palette.danger
            entry.seenAt > 0L -> Palette.success
            else -> Palette.warning
        }
        details.addView(ui.label(injectionText, 10, injectionColor, false).apply {
            setPadding(0, ui.dp(2), 0, 0)
            isClickable = true
            isFocusable = false
            setOnClickListener { checkInjection(entry) }
        })

        left.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(left, LinearLayout.LayoutParams(0, -1, 1f))

        val right = LinearLayout(ui.activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val on = entry.enabled
        val statusChip = ui.chip(
            if (on) "模块已启用" else "未启用",
            if (on) Palette.success else Palette.muted,
            if (on) Palette.successSoft else Palette.surfaceMuted
        ).apply {
            isClickable = true
        }
        statusChip.setOnClickListener {
            if (!canRoot) {
                host.toast("需要 Root / Shizuku 模式")
                return@setOnClickListener
            }
            val target = !entry.enabled
            // 先按目标状态更新界面，root 写入放后台，失败再回滚并提示
            applyChip(statusChip, target)
            Thread {
                val ok = setEnabled(entry, target)
                ui.activity.runOnUiThread {
                    if (ui.activity.isFinishing || ui.activity.isDestroyed) return@runOnUiThread
                    if (ok) {
                        entry.enabled = target
                        host.toast(if (target) "模块已启用，重启目标应用生效" else "模块已停用")
                    } else {
                        applyChip(statusChip, !target)
                        host.toast("写入失败，检查 Root / Shizuku 授权")
                    }
                }
            }.start()
        }
        right.addView(statusChip)
        row.addView(right, LinearLayout.LayoutParams(ui.dp(76), -1))

        card.addView(row)
        return card
    }

    private fun applyChip(chip: android.widget.TextView, on: Boolean) {
        chip.text = if (on) "模块已启用" else "未启用"
        chip.setTextColor(if (on) Palette.success else Palette.muted)
        chip.background = ui.rounded(if (on) Palette.successSoft else Palette.surfaceMuted, 10)
    }

    private fun checkInjection(entry: Entry) {
        if (!WorkMode.canUseRoot(ui.activity)) {
            host.toast("需要 Root / Shizuku 模式才能检查 LSPosed 作用域")
            return
        }
        if (!entry.enabled) {
            ui.dialog(
                title = "模块未启用",
                message = "先在本页把「${entry.label}」的模块开关打开，再检查 LSPosed 作用域。",
                confirmText = "知道了",
                cancelText = null
            )
            return
        }
        Thread {
            val seenAt = try {
                TargetFiles.load(entry.pkg, force = true).seenAt
            } catch (_: Throwable) {
                0L
            }
            LsposedScope.invalidate(entry.pkg)
            val inScope = LsposedScope.isInScope(ui.activity, entry.pkg, force = true)
            ui.activity.runOnUiThread {
                if (ui.activity.isFinishing || ui.activity.isDestroyed) return@runOnUiThread
                if (seenAt > 0L && inScope != false) {
                    host.toast("已检测到「${entry.label}」的注入")
                    return@runOnUiThread
                }
                when (inScope) {
                    false -> ui.dialog(
                        title = "LSPosed 作用域未勾选",
                        message = "「${entry.label}」不在 LSPosed 作用域里，模块不会被注入。\n\n" +
                            "打开 LSPosed，勾选这个应用，然后启动一次目标应用。",
                        confirmText = "打开 LSPosed",
                        cancelText = "稍后",
                        onConfirm = { host.openLsposed() }
                    )

                    true -> ui.dialog(
                        title = "已勾选，等待注入",
                        message = "LSPosed 作用域已经包含「${entry.label}」，但还没有收到注入标记。\n\n" +
                            "启动一次目标应用后即可检测；如果已启动仍然提示，请重启目标应用。",
                        confirmText = "知道了",
                        cancelText = null
                    )

                    null -> ui.dialog(
                        title = "请确认 LSPosed 作用域",
                        message = "暂时无法读取 LSPosed 的作用域配置。\n\n" +
                            "请确认已勾选「${entry.label}」，并启动一次目标应用。",
                        confirmText = "打开 LSPosed",
                        cancelText = "知道了",
                        onConfirm = { host.openLsposed() }
                    )
                }
            }
        }.start()
    }

    /** 主页开关 = 该应用的模块总开关，关闭时所有功能都不工作。调用方在后台线程执行。 */
    private fun setEnabled(entry: Entry, checked: Boolean): Boolean {
        val ok = Features.setRemote(entry.pkg, Features.MASTER, checked)
        TargetFiles.invalidate(entry.pkg)
        repo.log(entry.pkg, if (checked) "模块已启用" else "模块已停用")
        return ok
    }

    private fun confirmRemove(entry: Entry) {
        ui.dialog(
            title = "移除目标应用",
            message = "将从列表移除「${entry.label}」，并关闭它的功能开关。已有日志会保留。",
            confirmText = "移除",
            cancelText = "取消",
            onConfirm = {
                repo.removeTarget(entry.pkg)
                host.refresh()
            }
        )
    }
}
