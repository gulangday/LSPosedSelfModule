package com.example.selfxposed.ui

import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import com.example.selfxposed.Keywords
import com.example.selfxposed.KeywordTemplates
import com.example.selfxposed.WorkMode

/** 关键字拦截：按应用维护关键字，支持模板保存/删除/套用。 */
class KeywordScreen(
    private val host: ScreenHost,
    private val pkg: String,
    private val name: String
) {
    private val ui get() = host.ui

    fun render(into: LinearLayout) {
        into.addView(ui.backRow("‹  返回") { host.openUpdateBlock(pkg, name) })
        into.addView(ui.sectionTitle("关键字 · $name"))

        if (!WorkMode.canUseRoot(ui.activity)) {
            into.addView(ui.hint("当前模式没有 root 能力，关键字需要写入目标应用，暂不可用。"))
            into.addView(ui.lockedCard("关键字", "在设置 → 工作模式切换到 Root 或 Shizuku 模式后可用"))
            return
        }

        val keywords = Keywords.read(pkg).toMutableList()
        into.addView(ui.hint("弹窗标题或内容命中任意一个关键字就会被拦掉（不区分大小写）。"))

        fun saveAndRefresh(list: List<String>) {
            Keywords.write(ui.activity, pkg, list)
            host.refresh()
        }

        val input = EditText(ui.activity).apply {
            hint = "输入关键字，如 发现新版本"
            textSize = 15f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8))
            background = null
        }
        into.addView(ui.card().apply {
            addView(ui.label("添加关键字", 13, Palette.muted, true))
            addView(input, LinearLayout.LayoutParams(-1, -2))
        })
        into.addView(ui.actionCard("添加", "加入当前应用的关键字列表") {
            val value = input.text.toString().trim()
            if (value.isEmpty()) {
                host.toast("请输入关键字")
                return@actionCard
            }
            if (keywords.contains(value)) {
                host.toast("已存在")
                return@actionCard
            }
            keywords.add(value)
            saveAndRefresh(keywords)
        })

        if (keywords.isEmpty()) {
            into.addView(ui.card().apply { addView(ui.label("还没有关键字", 14, Palette.muted, false)) })
        } else {
            keywords.forEach { keyword ->
                into.addView(ui.actionCard(keyword, "点击删除") {
                    ui.dialog(
                        title = "删除关键字",
                        message = "删除「$keyword」后，这条文字不再触发拦截。",
                        confirmText = "删除",
                        cancelText = "取消",
                        onConfirm = {
                            keywords.remove(keyword)
                            saveAndRefresh(keywords)
                        }
                    )
                })
            }
        }

        into.addView(ui.sectionTitle("模板"))
        val templates = KeywordTemplates.list(ui.activity)
        into.addView(ui.actionCard("存为模板", "把当前列表保存为模板：$name") {
            KeywordTemplates.save(ui.activity, name, keywords.toList())
            host.toast("已保存模板")
            host.refresh()
        })
        if (templates.isNotEmpty()) {
            val names = templates.keys.toList()
            into.addView(ui.actionCard("套用模板", "把某个模板的关键字写入当前应用") {
                ui.listDialog(
                    title = "选择模板",
                    items = names,
                    onPick = { which ->
                        val template = names[which]
                        val merged = (keywords + templates[template].orEmpty()).distinct()
                        saveAndRefresh(merged)
                    }
                )
            })
            into.addView(ui.actionCard("删除模板", "删除已保存的关键字模板") {
                ui.listDialog(
                    title = "删除模板",
                    items = names,
                    onPick = { which ->
                        KeywordTemplates.remove(ui.activity, names[which])
                        host.toast("已删除模板 ${names[which]}")
                        host.refresh()
                    }
                )
            })
        }
    }
}
