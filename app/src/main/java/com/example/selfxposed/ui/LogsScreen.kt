package com.example.selfxposed.ui

import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout

/**
 * 日志页。
 *
 * 两种用法：
 * - [feature] 不为空：只看这个功能的日志（功能页的「查看日志」）。
 * - [feature] 为空且 [pkg] 是模块自己：看整个模块的日志，把所有目标应用的记录汇总在一起（设置页）。
 * - [feature] 为空且 [pkg] 是某个目标应用：看这个应用的全部日志（应用设置页）。
 *
 * 日志仍是一整块可长按选中的文本，另有「一键复制全部日志」按钮。
 */
class LogsScreen(
    private val host: ScreenHost,
    private val pkg: String,
    private val name: String,
    private val feature: String? = null
) {
    private val ui get() = host.ui
    private val repo get() = host.repo

    fun render(into: LinearLayout) {
        into.addView(ui.backRow("‹  返回") { host.backFromLogs() })

        val isModule = pkg == com.example.selfxposed.ConfigClient.MODULE_PACKAGE
        val title = when {
            feature != null -> "$name · $feature 日志"
            isModule -> "模块日志（全部）"
            else -> "$name · 日志"
        }
        into.addView(ui.sectionTitle(title))

        val lines = collectLines(isModule)
        val body = lines.joinToString("\n").ifBlank {
            when {
                feature != null -> "「$feature」还没有运行记录。打开目标应用并触发这个功能后会显示在这里。"
                isModule -> "暂无日志。添加目标应用并触发模块功能后，记录会显示在这里。"
                else -> "暂无日志。打开目标应用并触发模块功能后，记录会显示在这里。"
            }
        }
        val hasLogs = lines.isNotEmpty()

        // 用只读文本框来承载日志。
        // 之前用「可选中文字 + 外层滚动条」的组合，两者抢同一个拖动手势，
        // 结果是拖动时文字只顾着处理选区，日志框滚不动。
        // 文本框天生同时支持「拖动滚动」和「长按选中」，不需要再套滚动条。
        val text = android.widget.EditText(ui.activity).apply {
            setText(body)
            // 只读：去掉键盘输入能力，光标记不显示
            setKeyListener(null)
            isCursorVisible = false
            isFocusable = false
            isFocusableInTouchMode = false
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setTextColor(Palette.ink)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.START or Gravity.TOP
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
            // 长按可以全选、也可以拖手柄选一段
            setTextIsSelectable(true)
            // 内容超出高度时在框内滚动
            isVerticalScrollBarEnabled = true
            scrollBarStyle = android.view.View.SCROLLBARS_INSIDE_INSET
            setHorizontallyScrolling(false)
            // 手指按在框里时不让外层页面抢走手势，否则会变成整页滚动
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN,
                    android.view.MotionEvent.ACTION_MOVE ->
                        view.parent?.requestDisallowInterceptTouchEvent(true)

                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL ->
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
        }

        // 日志框固定高度：超出就在框里滚动，下面的按钮不会被顶到看不见的地方。
        into.addView(ui.card().apply {
            setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
            addView(text, LinearLayout.LayoutParams(-1, ui.dp(LOG_BOX_HEIGHT_DP)))
        })

        into.addView(ui.hint(
            if (feature != null) {
                "这里只显示「$feature」的记录；想看全部记录，去 设置 → 模块日志。\n长按日志可以全选或拖动选一段。"
            } else {
                "长按上面的日志可以全选、也可以拖动手柄选一段复制。"
            }
        ))

        into.addView(ui.actionCard("一键复制全部日志", "把上面显示的日志整段复制到剪贴板") {
            if (!hasLogs) {
                host.toast("没有日志可复制")
                return@actionCard
            }
            copy(body)
        })

        into.addView(ui.actionCard("刷新日志", "重新读取当前记录") { host.refresh() })
        into.addView(ui.actionCard("清空日志", clearHint(isModule)) {
            ui.dialog(
                title = "清空日志",
                message = clearMessage(isModule),
                confirmText = "清空",
                cancelText = "取消",
                onConfirm = {
                    clearLogs(isModule)
                    host.toast("已清空")
                    host.refresh()
                }
            )
        })
    }

    /** 按当前页面的范围取出日志行（已倒序，最新的在前）。 */
    private fun collectLines(isModule: Boolean): List<String> {
        val result = ArrayList<String>()
        try {
            if (feature != null) {
                // 只留这个功能的：写日志时加了 [功能名] 前缀
                val marker = "[$feature]"
                result += splitLines(repo.logs(pkg)).filter { it.contains(marker) }
            } else if (isModule) {
                // 整个模块：模块自身记录 + 每个目标应用的记录，都加上来源名称
                result += splitLines(repo.moduleLogs()).map { "模块  $it" }
                repo.targets().forEach { target ->
                    val label = try {
                        repo.label(target)
                    } catch (_: Throwable) {
                        target
                    }
                    result += splitLines(repo.logs(target)).map { "$label  $it" }
                }
            } else {
                result += splitLines(repo.logs(pkg))
            }
        } catch (_: Throwable) {
        }
        return result.asReversed()
    }

    private fun splitLines(raw: String): List<String> =
        raw.lines().filter { it.isNotBlank() }

    private fun clearHint(isModule: Boolean): String = when {
        feature != null -> "只清空当前应用的记录，其它功能不受影响"
        isModule -> "清空模块自身和所有目标应用的记录"
        else -> "只清空当前应用的记录"
    }

    private fun clearMessage(isModule: Boolean): String = when {
        feature != null -> "将删除「$name」的全部运行记录（包含其它功能），不可恢复。"
        isModule -> "将删除模块自身和所有目标应用的运行记录，不可恢复。"
        else -> "将删除「$name」的全部运行记录，不可恢复。"
    }

    private fun clearLogs(isModule: Boolean) {
        try {
            if (isModule) {
                repo.clearModuleLogs()
                repo.targets().forEach { repo.clearLogs(it) }
            } else {
                repo.clearLogs(pkg)
            }
        } catch (_: Throwable) {
        }
    }

    /** 复制整段日志到剪贴板。 */
    private fun copy(text: String) {
        try {
            val manager = ui.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager
            if (manager == null) {
                host.toast("这个设备不支持复制")
                return
            }
            manager.setPrimaryClip(android.content.ClipData.newPlainText("自用模块日志", text))
            host.toast("已复制全部日志")
        } catch (_: Throwable) {
            host.toast("复制失败")
        }
    }

    companion object {
        /** 日志框固定高度（dp）。超出就框内滚动，不把下面的按钮顶走。 */
        private const val LOG_BOX_HEIGHT_DP = 320
    }
}
