package com.example.selfxposed.ui

import android.widget.LinearLayout
import android.widget.Switch
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

        // ---- 最上面的公告：讲清楚规则怎么来的 ----
        into.addView(ui.card(Palette.hero).apply {
            elevation = 0f
            addView(ui.label("弹窗规则怎么工作", 12, Palette.accent, true))
            addView(ui.label("安装后第一次遇到弹窗时", 19, Palette.ink, true).apply {
                setPadding(0, ui.dp(6), 0, ui.dp(6))
            })
            addView(ui.label(
                "模块会先问你要不要拦截：\n" +
                    "「全部拦截」——该应用的弹窗以后都拦掉\n" +
                    "「只拦这个」——只拦标题相同的弹窗\n" +
                    "「不拦」——放行，不再询问\n\n" +
                    "规则存在目标应用自己的数据目录里，所以查看或清除需要 root 能力。",
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

        into.addView(ui.sectionTitle("拦截方式"))
        if (canRoot) {
            into.addView(ui.actionCard("关键字", "自定义要拦截的文字，命中即拦") {
                host.openKeywords(pkg, name)
            })
        } else {
            into.addView(ui.lockedCard("关键字", "自定义要拦截的文字，命中即拦"))
        }

        // 提示样式：预设不需要 root，始终可进；只有里面的自定义样式需要 root 能力
        into.addView(ui.actionCard(
            "拦截提示样式",
            if (canRoot) "字号、位置、背景色" else "预设可用；自定义样式需要 root"
        ) {
            host.openHintStyle(pkg, name)
        })

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
