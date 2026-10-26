package com.example.selfxposed.ui

import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.example.selfxposed.Features
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
        val version: String?
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

        // 后台补读功能开关状态：首帧先用缓存，读到与当前显示不一致时再刷新（避免无限刷新）
        if (canRootHome) {
            val rendered = entries.associate { it.pkg to it.enabled }
            Thread {
                var changed = false
                entries.forEach { entry ->
                    val fresh = try {
                        TargetFiles.load(entry.pkg, force = true)
                    } catch (_: Throwable) {
                        null
                    } ?: return@forEach
                    if (fresh.isOn(Features.MASTER) != rendered[entry.pkg]) changed = true
                }
                if (changed) ui.activity.runOnUiThread { host.refresh() }
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

        left.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(left, LinearLayout.LayoutParams(0, -1, 1f))

        val right = LinearLayout(ui.activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val canRoot = WorkMode.canUseRoot(ui.activity)
        val on = entry.enabled
        val statusChip = ui.chip(
            if (on) "模块已启用" else "未启用",
            if (on) Palette.success else Palette.muted,
            if (on) Palette.successSoft else Palette.surfaceMuted
        ).apply {
            isClickable = true
        }
        statusChip.setOnClickListener {
            val target = !entry.enabled
            val ok = toggle(entry, target)
            if (ok) {
                // 只更新这两个标签，不重建整页
                entry.enabled = target
                statusChip.text = if (target) "模块已启用" else "未启用"
                statusChip.setTextColor(if (target) Palette.success else Palette.muted)
                statusChip.background = ui.rounded(
                    if (target) Palette.successSoft else Palette.surfaceMuted, 10
                )
            }
        }
        right.addView(statusChip)
        row.addView(right, LinearLayout.LayoutParams(ui.dp(76), -1))

        card.addView(row)
        return card
    }

    private fun toggle(entry: Entry, checked: Boolean): Boolean {
        if (!WorkMode.canUseRoot(ui.activity)) {
            host.toast("需要 Root / Shizuku 模式")
            return false
        }
        // 主页开关 = 该应用的模块总开关，关闭时所有功能都不工作
        val ok = Features.setRemote(entry.pkg, Features.MASTER, checked)
        TargetFiles.invalidate(entry.pkg)
        repo.log(entry.pkg, if (checked) "模块已启用" else "模块已停用")
        host.toast(
            if (ok) (if (checked) "模块已启用，重启目标应用生效" else "模块已停用")
            else "写入失败，检查 Root / Shizuku 授权"
        )
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
