package com.example.selfxposed.ui

import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import java.util.Locale

/** 主页：模块控制台 + 目标应用卡片列表。 */
class HomeScreen(private val host: ScreenHost) {
    private val ui get() = host.ui
    private val repo get() = host.repo

    private data class Entry(
        val pkg: String,
        val label: String,
        val installed: Boolean,
        val enabled: Boolean,
        val detected: Boolean,
        val version: String?
    )

    fun render(into: LinearLayout) {
        into.addView(heroCard())
        // 操作入口固定在列表上方，应用多了也不会被挤到最下面。
        into.addView(ui.actionCard("添加目标应用", "从已安装的启动器应用中选择") { host.openPicker() })
        into.addView(ui.actionCard("使用说明", "作用域与模块开关需要同时启用") { host.openHelp() })

        into.addView(ui.sectionTitle("目标应用"))
        into.addView(ui.hint("开关状态与 LSPosed 作用域是两个独立状态：作用域没有勾选时模块不会被注入，开关也不会生效。"))

        val entries = repo.targets().map { pkg ->
            val cfg = repo.config(pkg)
            Entry(
                pkg = pkg,
                label = repo.label(pkg),
                installed = repo.isInstalled(pkg),
                enabled = cfg.optBoolean("enabled", false),
                detected = repo.hookDetected(pkg),
                version = repo.versionName(pkg)
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
            entries.forEach { into.addView(appCard(it)) }
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

    private fun appCard(entry: Entry): View {
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

        val (chipText, chipColor, chipBackground) = when {
            !entry.installed -> Triple("未安装", Palette.muted, Palette.surfaceMuted)
            entry.detected -> Triple("Hook 已连接", Palette.success, Palette.successSoft)
            else -> Triple("待检测：先启动一次目标应用", Palette.warning, Palette.warningSoft)
        }
        details.addView(ui.chip(chipText, chipColor, chipBackground))

        left.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(left, LinearLayout.LayoutParams(0, -1, 1f))

        val right = LinearLayout(ui.activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        right.addView(
            ui.checkbox(entry.enabled, entry.installed, "${entry.label} 模块开关") { checked ->
                toggle(entry, checked)
            }
        )
        row.addView(right, LinearLayout.LayoutParams(ui.dp(56), -1))

        card.addView(row)
        return card
    }

    private fun toggle(entry: Entry, checked: Boolean) {
        val cfg = repo.config(entry.pkg)
        cfg.put("enabled", checked)
        repo.saveConfig(entry.pkg, cfg)
        repo.log(entry.pkg, if (checked) "已在模块设置中启用" else "已在模块设置中关闭")
    }

    private fun confirmRemove(entry: Entry) {
        ui.dialog(
            title = "移除目标应用",
            message = "将从列表移除「${entry.label}」，并关闭它的模块开关。已有日志会保留。",
            confirmText = "移除",
            cancelText = "取消"
        ) {
            repo.removeTarget(entry.pkg)
            host.refresh()
        }
    }
}
