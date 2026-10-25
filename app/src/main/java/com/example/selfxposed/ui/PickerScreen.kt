package com.example.selfxposed.ui

import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import com.example.selfxposed.data.AppEntry
import java.util.Locale

/** 添加目标应用：搜索 + 分页列出已安装的启动器应用。 */
class PickerScreen(private val host: ScreenHost) {
    private val ui get() = host.ui
    private val repo get() = host.repo

    fun render(into: LinearLayout) {
        into.addView(ui.backRow("‹  返回主页") { host.openHome() })
        into.addView(ui.sectionTitle("添加目标应用"))
        into.addView(ui.hint("选择要注入弹窗的应用。添加后还需要在 LSPosed 作用域里勾选它，否则不会生效。"))

        val search = EditText(ui.activity).apply {
            hint = "搜索应用名称或包名"
            isSingleLine = true
            textSize = 14f
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            background = ui.rounded(Palette.background, 12)
        }
        into.addView(ui.card().apply { addView(search) })

        val listContainer = LinearLayout(ui.activity).apply { orientation = LinearLayout.VERTICAL }
        into.addView(listContainer)

        val remaining = repo.launchableApps().filter { entry ->
            entry.pkg !in repo.targets()
        }.toMutableList()

        var visible = PAGE_SIZE

        fun renderList() {
            val query = search.text.toString().trim().lowercase(Locale.ROOT)
            val filtered = remaining.filter { entry ->
                query.isEmpty() ||
                    entry.label.lowercase(Locale.ROOT).contains(query) ||
                    entry.pkg.lowercase(Locale.ROOT).contains(query)
            }
            listContainer.removeAllViews()
            if (filtered.isEmpty()) {
                listContainer.addView(ui.card().apply {
                    addView(
                        ui.label(
                            if (remaining.isEmpty()) "已把全部可用的应用都添加进来了" else "没有匹配的应用",
                            14, Palette.muted, false
                        )
                    )
                })
                return
            }
            filtered.take(visible).forEach { entry ->
                listContainer.addView(appRow(entry) {
                    repo.addTarget(entry.pkg)
                    repo.log(entry.pkg, "已添加为目标应用")
                    remaining.remove(entry)
                    host.toast("已添加 ${entry.label}，记得在 LSPosed 作用域中勾选")
                    renderList()
                })
            }
            if (filtered.size > visible) {
                listContainer.addView(ui.actionCard("显示更多", "还有 ${filtered.size - visible} 个应用") {
                    visible += PAGE_SIZE
                    renderList()
                })
            }
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                visible = PAGE_SIZE
                renderList()
            }
        })

        renderList()
    }

    private fun appRow(entry: AppEntry, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(ui.activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
            background = ui.rounded(Palette.surface, 16)
            isClickable = true
            isFocusable = true
            contentDescription = "添加 ${entry.label}"
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) }
        }

        repo.icon(entry.pkg)?.let { drawable ->
            row.addView(ui.icon(drawable, 36).apply {
                (layoutParams as LinearLayout.LayoutParams).rightMargin = ui.dp(10)
            })
        }

        val texts = LinearLayout(ui.activity).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(ui.label(entry.label, 14, Palette.ink, true))
        texts.addView(ui.label(entry.pkg, 10, Palette.muted, false))
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))

        row.addView(ui.label("＋", 20, Palette.accent, true).apply {
            gravity = Gravity.CENTER
            setPadding(ui.dp(8), 0, ui.dp(2), 0)
        })
        return row
    }

    companion object {
        private const val PAGE_SIZE = 60
    }
}
