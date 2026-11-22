package com.example.selfxposed.ui

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.example.selfxposed.PurifyRules
import com.example.selfxposed.TargetFiles
import com.example.selfxposed.WorkMode

/**
 * 净化规则详情页。
 *
 * 规则列表里每张卡片都能点进来，把一个规则的所有字段摊开；
 * 被黑屏保护拦下时，在这里说明原因、给改法，并提供开关和删除。
 */
class PurifyRuleScreen(
    private val host: ScreenHost,
    private val pkg: String,
    private val name: String,
    private val ruleId: String
) {
    private val ui get() = host.ui

    fun render(into: LinearLayout) {
        into.addView(ui.backRow("‹  净化") { host.openPurify(pkg, name) })
        into.addView(ui.sectionTitle("规则详情 · $name"))
        into.addView(ui.label(pkg, 12, Palette.muted, false).apply { setPadding(ui.dp(2), 0, 0, 0) })

        if (!WorkMode.canUseRoot(ui.activity)) {
            into.addView(ui.lockedCard("规则详情", "读写规则需要 Root / Shizuku 模式"))
            return
        }

        val state = TargetFiles.peek(pkg)
        if (state == null) {
            if (TargetFiles.failedRecently(pkg)) {
                into.addView(ui.hint("读取规则失败，请检查 Root / Shizuku 授权。"))
                into.addView(ui.actionCard("重试", "重新读取规则") {
                    TargetFiles.invalidate(pkg)
                    host.refresh()
                })
            } else {
                into.addView(ui.hint("正在读取规则…"))
                TargetFiles.loadAsync(ui.activity, pkg, force = true) { host.refresh() }
            }
            return
        }
        val rule = state.purifyRules.firstOrNull { it.id == ruleId }
        if (rule == null) {
            into.addView(ui.hint("这条规则已经不在了，可能刚被删除。"))
            into.addView(ui.actionCard("返回净化页", "查看其它规则") { host.openPurify(pkg, name) })
            return
        }

        // ---- 被黑屏保护拦下时，先说清楚原因和改法 ----
        if (rule.skipped.isNotBlank()) {
            into.addView(ui.card(Palette.warningSoft).apply {
                addView(ui.label("⚠ 这条规则当前没有生效", 15, Palette.warning, true))
                addView(ui.label(rule.skipped, 12, Palette.warning, false).apply {
                    setPadding(0, ui.dp(8), 0, 0)
                })
                addView(ui.label(
                    "原因：这条规则匹配到的控件几乎占满整个屏幕。把它隐藏或移除，界面会整片空白（黑屏），" +
                        "所以模块主动跳过了它。\n\n" +
                        "怎么办：\n" +
                        "1. 回目标应用点悬浮球 →「点选净化」\n" +
                        "2. 点同一个位置，用「◀ 更小」逐级缩小，选里面真正的广告控件\n" +
                        "3. 选中后按「✓ 用它」重新建规则\n" +
                        "4. 这条旧规则可以删掉",
                    12, Palette.ink, false
                ).apply { setPadding(0, ui.dp(10), 0, 0) })
            })
        }

        // ---- 控件小图：点一下放大 ----
        val shotView = android.widget.ImageView(ui.activity).apply {
            adjustViewBounds = true
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Palette.surfaceMuted)
            visibility = View.GONE
            setOnClickListener {
                val image = tag as? android.graphics.Bitmap ?: return@setOnClickListener
                ui.imageDialog(rule.summary(), image)
            }
        }
        into.addView(ui.card().apply {
            addView(ui.label("控件样子", 14, Palette.ink, true))
            addView(shotView, LinearLayout.LayoutParams(-1, ui.dp(160)).apply {
                topMargin = ui.dp(8)
            })
            addView(ui.label("点图片可以放大", 10, Palette.muted, false).apply {
                setPadding(0, ui.dp(6), 0, 0)
            })
        })
        loadShot(shotView, rule.id)

        // ---- 规则字段 ----
        into.addView(ui.card().apply {
            val switchRow = LinearLayout(ui.activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            switchRow.addView(
                ui.label("规则开关", 14, Palette.ink, true),
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            switchRow.addView(ui.statusChip(rule.on) { checked ->
                setEnabled(checked, rule)
                true
            }, LinearLayout.LayoutParams(-2, -2))
            addView(switchRow)

            addView(divider())
            addView(infoRow("匹配方式", PurifyRules.typeLabel(rule.type)))
            addView(infoRow("匹配值", rule.value.ifBlank { "（空）" }))
            addView(infoRow("动作", PurifyRules.actionLabel(rule.action)))
            addView(infoRow("参数", rule.arg.ifBlank { "（无）" }))
            addView(infoRow("生效界面", rule.activity.ifBlank { "所有界面" }))
            addView(infoRow("命中次数", if (rule.hits > 0) "${rule.hits} 次" else "从未命中"))
            addView(infoRow("规则 ID", rule.id))
        })

        if (rule.hits == 0 && rule.skipped.isBlank() && rule.on) {
            into.addView(ui.hint(
                "这条规则从未命中：目标应用里没有找到符合「${PurifyRules.typeLabel(rule.type)}：${rule.value}」的控件。\n" +
                    "可能控件还没加载出来、界面改版了，或者规则是给别的界面用的。"
            ))
        }

        if (!rule.on) {
            into.addView(ui.hint("开关关掉时这条规则不会执行，也不会统计命中。"))
        }

        into.addView(ui.actionCard("删除这条规则", "删除后不再生效，可重新点选创建") {
            confirmDelete(rule)
        })

        into.addView(ui.hint(
            "改完规则要重启目标应用才会长期生效；" +
                "当前界面里下一次布局变化时会先重新匹配一次。"
        ))
        into.addView(ui.actionCard("查看日志", "运行记录与错误信息") { host.openLogs(pkg, name, com.example.selfxposed.ConfigClient.F_PURIFY) })
    }

    private fun divider(): View = View(ui.activity).apply {
        setBackgroundColor(Palette.surfaceMuted)
        layoutParams = LinearLayout.LayoutParams(-1, ui.dp(1)).apply {
            topMargin = ui.dp(10)
            bottomMargin = ui.dp(10)
        }
    }

    private fun infoRow(label: String, value: String): View =
        LinearLayout(ui.activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ui.dp(4), 0, ui.dp(4))
            addView(
                ui.label(label, 12, Palette.muted, false),
                LinearLayout.LayoutParams(ui.dp(76), -2)
            )
            addView(
                ui.label(value, 12, Palette.ink, false),
                LinearLayout.LayoutParams(0, -2, 1f)
            )
        }

    /** 开关和删除都要写目标应用的文件，走后台线程（主线程不做 root IO）。 */
    private fun setEnabled(on: Boolean, rule: PurifyRules.Rule) {
        Thread {
            val all = PurifyRules.readRemote(pkg).toMutableList()
            val index = all.indexOfFirst { it.id == rule.id }
            val ok = if (index < 0) {
                false
            } else {
                all[index] = all[index].copy(on = on)
                PurifyRules.writeRemote(pkg, all)
            }
            TargetFiles.invalidate(pkg)
            ui.activity.runOnUiThread {
                host.toast(if (ok) "已保存，重启目标应用生效" else "写入失败，检查 Root / Shizuku 授权")
                if (ok) host.refresh()
            }
        }.start()
    }

    /** 读取这条规则的控件小图，没有图就不显示。 */
    private fun loadShot(view: android.widget.ImageView, ruleId: String) {
        val cacheDir = ui.activity.cacheDir
        Thread {
            PurifyRules.syncShots(pkg, cacheDir)
            val file = PurifyRules.localShot(cacheDir, ruleId)
            if (!file.exists() || file.length() == 0L) return@Thread
            val bitmap = try {
                android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            } catch (_: Throwable) {
                null
            } ?: return@Thread
            ui.activity.runOnUiThread {
                view.tag = bitmap
                view.setImageBitmap(bitmap)
                view.visibility = android.view.View.VISIBLE
            }
        }.start()
    }

    /** 删除必须二次确认（删除类操作统一要求）。 */
    private fun confirmDelete(rule: PurifyRules.Rule) {
        ui.dialog(
            title = "删除规则",
            message = "确定删除这条规则吗？\n\n" + rule.summary(),
            confirmText = "删除",
            cancelText = "取消",
            onConfirm = {
                Thread {
                    val rest = PurifyRules.readRemote(pkg).filterNot { it.id == rule.id }
                    val ok = PurifyRules.writeRemote(pkg, rest)
                    TargetFiles.invalidate(pkg)
                    ui.activity.runOnUiThread {
                        host.toast(if (ok) "已删除规则" else "写入失败，检查 Root / Shizuku 授权")
                        if (ok) host.openPurify(pkg, name) else host.refresh()
                    }
                }.start()
            }
        )
    }
}
