package com.example.selfxposed.ui

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.example.selfxposed.Features
import com.example.selfxposed.PurifyRules
import com.example.selfxposed.TargetFiles
import com.example.selfxposed.WorkMode

/**
 * 净化页：每个目标应用一页。
 *
 * 三层开关：主页「模块已启用」→ 本页「净化」→ 单条规则。
 * 规则由目标应用里的悬浮球点选生成（MainHook 侧写入），这里负责查看、开关、删除、导入导出。
 */
class PurifyScreen(
    private val host: ScreenHost,
    private val pkg: String,
    private val name: String
) {
    private val ui get() = host.ui

    fun render(into: LinearLayout) {
        into.addView(ui.backRow("‹  功能") { host.openFeatures() })
        into.addView(ui.sectionTitle("净化 · $name"))
        into.addView(ui.label(pkg, 12, Palette.muted, false).apply { setPadding(ui.dp(2), 0, 0, 0) })

        // 第一次进页面时 Root / Shizuku 可能还没绑定好，先置灰避免显示假的"未启用"
        if (!WorkMode.canUseRoot(ui.activity)) {
            into.addView(ui.lockedCard(
                "净化",
                "点选隐藏或改写界面上的广告位、横幅、牛皮癣按钮",
                tag = "需要 Root / Shizuku 模式"
            ))
            into.addView(ui.hint("净化要读写目标应用自己的数据目录，请先在设置里切到 Root 或 Shizuku 模式。"))
            return
        }

        val state = TargetFiles.load(pkg)
        if (!state.isOn(Features.MASTER)) {
            into.addView(ui.card(Palette.hero).apply {
                elevation = 0f
                addView(ui.label("模块未启用，净化不会生效", 15, Palette.ink, true))
                addView(ui.label(
                    "先在主页把该应用的「模块已启用」打开。",
                    12, Palette.muted, false
                ).apply { setPadding(0, ui.dp(8), 0, 0) })
            })
            into.addView(ui.lockedCard("净化", "点选隐藏或改写界面上的广告位、横幅", tag = "模块未启用"))
            into.addView(ui.actionCard("查看日志", "运行记录与错误信息") { host.openLogs(pkg, name, com.example.selfxposed.ConfigClient.F_PURIFY) })
            return
        }

        val rules = state.purifyRules.toMutableList()

        // ---- 功能总开关（三层里的第二层）----
        val chip = ui.statusChip(state.isOn(Features.PURIFY)) { checked ->
            val ok = Features.setRemote(pkg, Features.PURIFY, checked)
            TargetFiles.invalidate(pkg)
            host.toast(if (ok) "已保存，重启目标应用生效" else "写入失败，检查 Root / Shizuku 授权")
            ok
        }
        into.addView(ui.card().apply {
            val row = LinearLayout(ui.activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(
                ui.label("净化", 15, Palette.ink, true),
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            row.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(2) })
            addView(row)
            addView(ui.label(
                "点选界面上的控件来隐藏或改写它。规则按应用保存，只影响当前应用。",
                11, Palette.muted, false
            ).apply { setPadding(0, ui.dp(6), 0, 0) })
        })

        // 净化点选需要悬浮球；长按球会关闭它，这里提供开关和恢复入口
        val ballPath = "/data/data/$pkg/files/selfxposed_ball_off"
        val ballChip = ui.statusChip(!state.ballOff) { checked ->
            val command = if (checked) "rm -f $ballPath; echo ok" else "touch $ballPath; echo ok"
            val out = com.example.selfxposed.RootShell.exec(command)
            com.example.selfxposed.TargetFiles.invalidate(pkg)
            host.toast(
                when {
                    !out.contains("ok") -> "操作失败，检查 Root / Shizuku 授权"
                    checked -> "悬浮球已开启，重启目标应用后显示"
                    else -> "悬浮球已关闭"
                }
            )
            out.contains("ok")
        }
        into.addView(ui.card().apply {
            val row = LinearLayout(ui.activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(
                ui.label("悬浮球", 15, Palette.ink, true),
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            row.addView(ballChip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(2) })
            addView(row)
            addView(ui.label(
                "点选净化靠它进入；长按悬浮球也会关闭，关闭后在这里重新打开。",
                11, Palette.muted, false
            ).apply { setPadding(0, ui.dp(6), 0, 0) })
        })

        // ---- 用法 ----
        into.addView(ui.card().apply {
            addView(ui.label("怎么用", 14, Palette.ink, true))
            addView(ui.label(
                "1. 打开目标应用，点悬浮球 →「点选净化」\n" +
                    "2. 点界面上要处理的位置，方框会实时贴住控件\n" +
                    "3. 框不准就用「◀ 更小 / 更大 ▶」换候选控件\n" +
                    "4. 「隐藏」先预览效果，再点一次恢复显示\n" +
                    "5. 「详情」看这个控件的信息（类名、ID、尺寸、占屏比例）\n" +
                    "6. 点「✓ 用它」选动作：隐藏 / 移除 / 改文字 / 改尺寸\n" +
                    "7. 底部工具条右侧「⠿」可拖动，放到顺手的位置\n" +
                    "8. 立刻生效；重启目标应用后长期生效",
                12, Palette.muted, false
            ).apply { setPadding(0, ui.dp(8), 0, 0) })
        })

        // ---- 规则列表 ----
        val rulesTitle = ui.sectionTitle("规则（${rules.size}）")
        into.addView(rulesTitle)
        val shotSlots = ArrayList<Pair<android.widget.ImageView, String>>()
        val shotTitles = HashMap<String, String>()
        if (rules.isEmpty()) {
            into.addView(ui.hint("还没有规则。用悬浮球的「点选净化」添加，或从下面导入规则文件。"))
        } else {
            rules.forEach { rule ->
                shotTitles[rule.id] = rule.summary()
                into.addView(ruleCard(rule, rules, shotSlots) { count ->
                    rulesTitle.text = "规则（$count）"
                })
            }
        }
        if (shotSlots.isNotEmpty()) loadShots(shotSlots, shotTitles)

        if (rules.any { it.hits > 0 }) {
            into.addView(ui.actionCard("清空命中数", "只清计数，规则保留") {
                Thread {
                    val ok = PurifyRules.clearHitsRemote(pkg)
                    TargetFiles.invalidate(pkg)
                    ui.activity.runOnUiThread {
                        host.toast(if (ok) "命中数已清空" else "操作失败，检查 Root / Shizuku 授权")
                        if (ok) host.refresh()
                    }
                }.start()
            })
        }

        // ---- 导入 / 导出 ----
        into.addView(ui.actionCard("导出规则", "导出成文本文件，可备份或分享") {
            host.exportPurifyRules(pkg, name, PurifyRules.exportText(rules))
        })
        into.addView(ui.actionCard("导入规则", "从文本文件追加规则，不覆盖现有的") {
            host.importPurifyRules(pkg, name)
        })

        into.addView(ui.hint(
            "限制：只能处理当前界面里的普通控件。\n" +
                "悬浮窗、WebView 内部、Flutter / Unity 自绘界面里的元素拦不到；\n" +
                "Jetpack Compose 界面整屏其实只有一个控件，也会选到整个屏幕；\n" +
                "点「详情」后最下面一行「候选」只有 1 个，就说明遇到了上面这几种情况；\n" +
                "列表滚动后新加载的控件会在下一次布局变化时重新匹配。"
        ))
        into.addView(ui.actionCard("查看日志", "运行记录与错误信息") { host.openLogs(pkg, name, com.example.selfxposed.ConfigClient.F_PURIFY) })
    }

    private fun ruleCard(
        rule: PurifyRules.Rule,
        rules: MutableList<PurifyRules.Rule>,
        shotSlots: MutableList<Pair<android.widget.ImageView, String>>,
        onCountChanged: (Int) -> Unit
    ): View {
        val container = ui.card()
        container.addView(ui.label(rule.summary(), 13, Palette.ink, true))

        // 控件小图：先占位，读到图之后再显示；点一下放大
        val shot = android.widget.ImageView(ui.activity).apply {
            scaleType = android.widget.ImageView.ScaleType.FIT_START
            adjustViewBounds = true
            visibility = View.GONE
            setBackgroundColor(Palette.surfaceMuted)
            setPadding(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(2))
            setOnClickListener {
                val image = tag as? android.graphics.Bitmap ?: return@setOnClickListener
                ui.imageDialog(rule.summary(), image)
            }
        }
        container.addView(shot, LinearLayout.LayoutParams(-2, ui.dp(64)).apply {
            topMargin = ui.dp(8)
        })
        shotSlots.add(shot to rule.id)

        val row = LinearLayout(ui.activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            ui.label(if (rule.hits > 0) "命中 ${rule.hits} 次" else "未命中", 11, Palette.muted, false),
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        val chip = ui.statusChip(rule.on) { checked ->
            val index = rules.indexOfFirst { it.id == rule.id }
            if (index >= 0) {
                rules[index] = rules[index].copy(on = checked)
                save(rules, if (checked) "规则已开启" else "规则已关闭")
            }
            true
        }
        row.addView(chip, LinearLayout.LayoutParams(-2, -2))
        container.addView(row)

        // 被黑屏保护拦下时明确写出来，否则只看到"未命中"，分不清是没匹配到还是被跳过
        if (rule.skipped.isNotBlank()) {
            container.addView(ui.chip("⚠ 未生效：${rule.skipped}", Palette.warning, Palette.warningSoft))
            container.addView(ui.label(
                "这条规则匹配到了控件，但目标控件太大，隐藏会让界面空白，所以被拦下了。点击查看详情。",
                10, Palette.muted, false
            ).apply { setPadding(0, ui.dp(4), 0, 0) })
        } else if (!rule.on) {
            container.addView(ui.label("这条规则已关闭，不会生效。", 10, Palette.muted, false).apply {
                setPadding(0, ui.dp(6), 0, 0)
            })
        }

        container.addView(ui.label("点击看详情 · 长按删除", 10, Palette.muted, false).apply {
            setPadding(0, ui.dp(6), 0, 0)
        })
        ui.pressable(
            container,
            { host.openPurifyRule(pkg, name, rule.id) },
            { confirmDelete(rule, rules, container, onCountChanged) }
        )
        return container
    }

    /** 删除 / 清空类操作必须二次确认。 */
    private fun confirmDelete(
        rule: PurifyRules.Rule,
        rules: MutableList<PurifyRules.Rule>,
        card: View,
        onCountChanged: (Int) -> Unit
    ) {
        ui.dialog(
            title = "删除规则",
            message = "确定删除这条规则吗？\n\n" + rule.summary(),
            confirmText = "删除",
            cancelText = "取消",
            onConfirm = {
                rules.removeAll { it.id == rule.id }
                val remaining = rules.size
                save(rules, "已删除规则") {
                    // 只移除这张卡片，不重建整页，避免界面弹一下
                    val parent = card.parent as? android.view.ViewGroup
                    card.animate().alpha(0f).setDuration(180).withEndAction {
                        parent?.removeView(card)
                    }.start()
                    onCountChanged(remaining)
                }
            }
        )
    }

    /** 写规则走后台线程（§4.5 主线程不做 root IO）；快照在主线程取，避免并发改列表。 */
    private fun save(
        rules: List<PurifyRules.Rule>,
        message: String,
        onSaved: (() -> Unit)? = null
    ) {
        // 写之前先去重：同样的规则只留最新一条
        val snapshot = PurifyRules.dedupe(rules.toList())
        Thread {
            val ok = PurifyRules.writeRemote(pkg, snapshot)
            TargetFiles.invalidate(pkg)
            ui.activity.runOnUiThread {
                host.toast(if (ok) message else "写入失败，检查 Root / Shizuku 授权")
                if (ok) onSaved?.invoke()
            }
        }.start()
    }

    /**
     * 读取控件小图并显示。
     *
     * 目标应用的目录模块自己读不到，所以先用 root/Shizuku 把图拷到模块缓存里，
     * 再在本地解码显示。没图的规则保持隐藏，不会留下空白框。
     */
    private fun loadShots(
        slots: List<Pair<android.widget.ImageView, String>>,
        titles: Map<String, String>
    ) {
        val cacheDir = ui.activity.cacheDir
        Thread {
            PurifyRules.syncShots(pkg, cacheDir)
            // 顺手清掉已经不存在的规则留下的图，避免越攒越多
            PurifyRules.pruneShotsRemote(pkg, slots.map { it.second })
            slots.forEach { (view, ruleId) ->
                val file = PurifyRules.localShot(cacheDir, ruleId)
                if (!file.exists() || file.length() == 0L) return@forEach
                val bitmap = try {
                    android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                } catch (_: Throwable) {
                    null
                } ?: return@forEach
                ui.activity.runOnUiThread {
                    view.tag = bitmap
                    view.setImageBitmap(bitmap)
                    view.visibility = View.VISIBLE
                    view.contentDescription = titles[ruleId] ?: ruleId
                }
            }
        }.start()
    }
}
