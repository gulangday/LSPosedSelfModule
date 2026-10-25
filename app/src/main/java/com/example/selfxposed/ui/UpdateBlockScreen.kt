package com.example.selfxposed.ui

import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.example.selfxposed.WorkMode
import org.json.JSONObject

/**
 * 拦截弹窗设置页。
 *
 * 页面结构：弹窗规则公告 → 启用拦截 → 保存 → 关键字 / 提示样式 / 规则管理 → 日志。
 */
class UpdateBlockScreen(
    private val host: ScreenHost,
    private val pkg: String,
    private val name: String
) {
    private val ui get() = host.ui
    private val repo get() = host.repo

    fun render(into: LinearLayout) {
        val cfg = repo.config(pkg)
        val ub = cfg.optJSONObject("updateBlock") ?: JSONObject()

        into.addView(ui.backRow("‹  功能") { host.openFeatures() })

        // ---- 最上面的公告：讲清楚现在怎么用 ----
        into.addView(ui.card(Palette.hero).apply {
            elevation = 0f
            addView(ui.label("怎么拦截弹窗", 12, Palette.accent, true))
            addView(ui.label("用悬浮球点选，不会挡住弹窗", 19, Palette.ink, true).apply {
                setPadding(0, ui.dp(6), 0, ui.dp(6))
            })
            addView(ui.label(
                "1. 进入目标应用，点屏幕右侧的「拦」悬浮球\n" +
                    "2. 再点那个要拦的弹窗\n" +
                    "3. 底部会显示它的标题、类型、所属界面，点「拦截这个」即可\n\n" +
                    "之后命中规则的弹窗会自动关闭并保存截图；" +
                    "长按被拦截后的提示条可以修改规则。",
                12, Palette.muted, false
            ))
        })

        into.addView(ui.sectionTitle("拦截弹窗 · $name"))
        into.addView(ui.label(pkg, 12, Palette.muted, false).apply { setPadding(ui.dp(2), 0, 0, 0) })

        val enableSwitch = Switch(ui.activity).apply {
            text = "启用拦截"
            textSize = 14f
            isChecked = ub.optBoolean("enabled", false)
            setPadding(0, ui.dp(4), 0, ui.dp(4))
        }
        into.addView(ui.card().apply { addView(enableSwitch) })

        into.addView(ui.actionCard("保存设置", "只影响 $name") {
            val block = JSONObject()
            block.put("enabled", enableSwitch.isChecked)
            cfg.put("updateBlock", block)
            repo.saveConfig(pkg, cfg)
            repo.log(pkg, "拦截弹窗配置已保存：启用=${enableSwitch.isChecked}")
            host.toast("已保存")
        })

        // ---- 以下功能都需要写目标应用的数据，没有 root 能力时置灰 ----
        val canRoot = WorkMode.canUseRoot(ui.activity)
        // 拦截方式：ball（悬浮窗）/ auto（自动拦截）
        var pickMode = if (canRoot) {
            com.example.selfxposed.RootShell.exec(
                "cat /data/data/$pkg/files/selfxposed_pick_mode.conf 2>/dev/null"
            ).trim().ifBlank { "ball" }
        } else {
            "ball"
        }

        into.addView(ui.sectionTitle("拦截方式"))
        if (canRoot) {
            val options = listOf("悬浮窗", "自动拦截")
            val segItems = mutableListOf<TextView>()
            val hintLabel = ui.label("", 11, Palette.muted, false)
            val segGroup = LinearLayout(ui.activity).apply {
                orientation = LinearLayout.HORIZONTAL
                background = ui.rounded(Palette.surfaceMuted, 16)
                setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3))
            }

            // 悬浮球卡片（可用）
            val path = "/data/data/$pkg/files/selfxposed_ball_off"
            val ballOff = com.example.selfxposed.RootShell.exec("test -f $path && echo yes").contains("yes")
            val ballSwitch = Switch(ui.activity).apply { isChecked = !ballOff }
            ballSwitch.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    com.example.selfxposed.RootShell.exec("rm -f $path")
                    host.toast("悬浮球已开启，重启目标应用后显示")
                } else {
                    com.example.selfxposed.RootShell.exec("touch $path")
                    host.toast("悬浮球已关闭")
                }
            }
            val ballCard = ui.card().apply {
                val row = LinearLayout(ui.activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                row.addView(
                    ui.label("悬浮球", 15, Palette.ink, true),
                    LinearLayout.LayoutParams(0, -2, 1f)
                )
                row.addView(ballSwitch)
                addView(row)
                addView(ui.label("长按悬浮球也可以关闭它", 11, Palette.muted, false).apply {
                    setPadding(0, ui.dp(4), 0, 0)
                })
            }
            val ballLocked = ui.lockedCard("悬浮球", "选择「悬浮窗」方式后启用")

            // 提示样式卡片（可用 / 置灰）
            val hintCard = ui.actionCard("拦截提示样式", "字号、位置、背景色") {
                host.openHintStyle(pkg, name)
            }
            val hintLocked = ui.lockedCard("拦截提示样式", "选择「自动拦截」方式后启用")

            fun applyMode(chosen: Int) {
                segItems.forEachIndexed { index, item ->
                    val active = index == chosen
                    item.background = ui.rounded(
                        if (active) Palette.accent else android.graphics.Color.TRANSPARENT, 13
                    )
                    item.setTextColor(if (active) android.graphics.Color.WHITE else Palette.muted)
                }
                hintLabel.text = if (chosen == 0) {
                    "点悬浮球，再点要拦截的弹窗"
                } else {
                    "弹窗出现时自动弹规则选择框"
                }
                // 局部切换可见性，避免重建页面导致卡顿和滚动回顶
                ballCard.visibility = if (chosen == 0) LinearLayout.VISIBLE else LinearLayout.GONE
                ballLocked.visibility = if (chosen == 0) LinearLayout.GONE else LinearLayout.VISIBLE
                hintCard.visibility = if (chosen == 1) LinearLayout.VISIBLE else LinearLayout.GONE
                hintLocked.visibility = if (chosen == 1) LinearLayout.GONE else LinearLayout.VISIBLE
            }

            options.forEachIndexed { index, label ->
                val item = ui.label(label, 12, Palette.muted, true).apply {
                    gravity = android.view.Gravity.CENTER
                    setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6))
                    isClickable = true
                    setOnClickListener {
                        pickMode = if (index == 0) "ball" else "auto"
                        com.example.selfxposed.RootShell.exec(
                            "mkdir -p /data/data/$pkg/files; echo $pickMode > /data/data/$pkg/files/selfxposed_pick_mode.conf"
                        )
                        applyMode(index)
                        host.toast("已切换为${options[index]}，重启目标应用生效")
                    }
                }
                segItems.add(item)
                segGroup.addView(item, LinearLayout.LayoutParams(-2, -2))
            }

            val textCol = LinearLayout(ui.activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(ui.label("拦截方式", 15, Palette.ink, true))
                addView(hintLabel.apply { setPadding(0, ui.dp(4), 0, 0) })
            }
            val row = LinearLayout(ui.activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))
                addView(segGroup, LinearLayout.LayoutParams(-2, -2).apply {
                    leftMargin = ui.dp(10)
                })
            }
            into.addView(ui.card().apply { addView(row) })
            into.addView(ballCard)
            into.addView(ballLocked)
            into.addView(hintCard)
            into.addView(hintLocked)
            applyMode(if (pickMode == "auto") 1 else 0)
        } else {
            into.addView(ui.lockedCard("拦截方式", "悬浮窗 / 自动拦截"))
        }

        if (canRoot) {
            into.addView(ui.actionCard("关键字", "自定义要拦截的文字，命中即拦") {
                host.openKeywords(pkg, name)
            })
        } else {
            into.addView(ui.lockedCard("关键字", "自定义要拦截的文字，命中即拦"))
        }

        // 提示样式只在「自动拦截」方式下才有意义（悬浮窗模式不显示拦截提示）
        if (canRoot) {
            // 两张卡片在拦截方式区块里创建并切换可见性，这里占位交给 applyMode
        } else {
            into.addView(ui.lockedCard("拦截提示样式", "需要 root 能力"))
        }

        if (canRoot) {
            into.addView(ui.actionCard("查看 / 清除规则（需要 root）", "该应用当前学到的拦截规则") {
                host.openDialogRules(pkg, name)
            })
        } else {
            into.addView(ui.lockedCard("查看 / 清除规则（需要 root）", "该应用当前学到的拦截规则"))
        }

        into.addView(ui.actionCard("查看日志", "运行记录与错误信息") { host.openLogs(pkg, name) })
    }

    companion object {
        fun pickTarget(host: ScreenHost) {
            val targets = host.repo.targets()
            if (targets.isEmpty()) {
                host.ui.dialog("还没有目标应用", "请先在主页添加目标应用。", confirmText = "知道了", cancelText = null)
                return
            }
            if (targets.size == 1) {
                host.openUpdateBlock(targets.first(), host.repo.label(targets.first()))
                return
            }
            val names = targets.map { host.repo.label(it) }
            host.ui.listDialog(
                title = "选择目标应用",
                items = names,
                onPick = { which -> host.openUpdateBlock(targets[which], names[which]) }
            )
        }
    }
}
