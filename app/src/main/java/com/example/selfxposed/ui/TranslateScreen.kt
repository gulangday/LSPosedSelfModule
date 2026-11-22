package com.example.selfxposed.ui

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import com.example.selfxposed.Features
import com.example.selfxposed.TargetFiles
import com.example.selfxposed.TranslateDict
import com.example.selfxposed.WorkMode

/**
 * 翻译页：每个目标应用一页。
 *
 * 纯离线：只查本地词库，不联网、不闪。翻不到的词保持英文原样。
 * 三层开关：主页「模块已启用」→ 本页「翻译」→ 无需第三层。
 */
class TranslateScreen(
    private val host: ScreenHost,
    private val pkg: String,
    private val name: String
) {
    private val ui get() = host.ui

    fun render(into: LinearLayout) {
        into.addView(ui.backRow("‹  功能") { host.openFeatures() })
        into.addView(ui.sectionTitle("翻译 · $name"))
        into.addView(ui.label(pkg, 12, Palette.muted, false).apply { setPadding(ui.dp(2), 0, 0, 0) })

        // 读写目标应用目录需要 root / Shizuku，拿不到就先置灰
        if (!WorkMode.canUseRoot(ui.activity)) {
            into.addView(ui.lockedCard(
                "翻译",
                "把界面上的英文换成中文",
                tag = "需要 Root / Shizuku 模式"
            ))
            into.addView(ui.hint("翻译要把词库写进目标应用自己的目录，请先在设置里切到 Root 或 Shizuku 模式。"))
            return
        }

        // 首帧只读缓存，没读到就让后台去读，不在主线程等 root 命令
        val state = TargetFiles.peek(pkg)
        if (state == null) {
            if (TargetFiles.failedRecently(pkg)) {
                into.addView(ui.hint("读取目标应用状态失败，请检查 Root / Shizuku 授权。"))
                into.addView(ui.actionCard("重试", "重新读取目标应用状态") {
                    TargetFiles.invalidate(pkg)
                    host.refresh()
                })
            } else {
                into.addView(ui.hint("正在读取目标应用状态…"))
                TargetFiles.loadAsync(ui.activity, pkg, force = true) { host.refresh() }
            }
            return
        }
        if (!state.isOn(Features.MASTER)) {
            into.addView(ui.card(Palette.hero).apply {
                elevation = 0f
                addView(ui.label("模块未启用，翻译不会生效", 15, Palette.ink, true))
                addView(ui.label(
                    "先在主页把该应用的「模块已启用」打开。",
                    12, Palette.muted, false
                ).apply { setPadding(0, ui.dp(8), 0, 0) })
            })
            into.addView(ui.lockedCard("翻译", "把界面上的英文换成中文", tag = "模块未启用"))
            into.addView(ui.actionCard("查看日志", "运行记录与错误信息") { host.openLogs(pkg, name, com.example.selfxposed.ConfigClient.F_TRANSLATE) })
            return
        }

        val entries = state.translateUser.toMutableMap()

        // ---- 功能总开关 ----
        val chip = ui.statusChip(state.isOn(Features.TRANSLATE)) { checked ->
            host.setFeatureAsync(pkg, Features.TRANSLATE, checked)
            true
        }
        into.addView(ui.card().apply {
            val row = LinearLayout(ui.activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(
                ui.label("翻译", 15, Palette.ink, true),
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            row.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(2) })
            addView(row)
            addView(ui.label(
                "把目标软件里的英文按钮、菜单、提示换成中文。只查本地词库，不联网。",
                11, Palette.muted, false
            ).apply { setPadding(0, ui.dp(6), 0, 0) })
        })

        // ---- 安全选项：带符号的不翻（默认开）----
        var skipSymbols = state.translateSkipSymbols
        val symbolChip = ui.statusChip(skipSymbols) { checked ->
            val ok = TranslateDict.writeRemoteOptions(pkg, checked)
            TargetFiles.invalidate(pkg)
            if (ok) skipSymbols = checked
            host.toast(if (ok) "已保存，重启目标应用生效" else "写入失败，检查 Root / Shizuku 授权")
            ok
        }
        into.addView(ui.card().apply {
            val row = LinearLayout(ui.activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(
                ui.label("带符号的不翻译", 14, Palette.ink, true),
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            row.addView(symbolChip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(2) })
            addView(row)
            addView(ui.label(
                "开着更安全：只要文字里出现 % { } < > & = \\ $ # @ | ~ ^ ` 这些符号，" +
                    "整条就跳过不翻。\n\n" +
                    "原因是「共 %d 项」「{0}」这类是程序的占位符，翻掉会让软件出错甚至闪退。\n\n" +
                    "关掉能多翻一点，但可能会崩。",
                11, Palette.muted, false
            ).apply { setPadding(0, ui.dp(6), 0, 0) })
        })

        // ---- 能翻什么、翻不到什么 ----
        into.addView(ui.card().apply {
            addView(ui.label("能翻什么", 14, Palette.ink, true))
            addView(ui.label(
                "能翻：按钮、菜单、标题、弹窗文字、设置项这类「文字控件」。\n\n" +
                    "翻不到：网页里的内容、用 Flutter / Unity 做的界面、游戏画面、图片里的字。\n" +
                    "这些地方的文字是画出来的，没有可替换的文字。\n\n" +
                    "不翻：网址、邮箱、版本号、纯数字。翻了反而会出错。",
                12, Palette.muted, false
            ).apply { setPadding(0, ui.dp(8), 0, 0) })
        })

        // ---- 词库概况 ----
        into.addView(ui.card().apply {
            addView(ui.label("词库", 14, Palette.ink, true))
            addView(ui.label(
                "内置 ${TranslateDict.builtinCount()} 条，你自己加了 ${entries.size} 条。",
                12, Palette.muted, false
            ).apply { setPadding(0, ui.dp(8), 0, 0) })
            addView(ui.label(
                "翻不到的词会保持英文。把常遇到的补进来就行，不用一次补全。",
                11, Palette.muted, false
            ).apply { setPadding(0, ui.dp(6), 0, 0) })
        })

        // ---- 试翻一下：不用来回切软件就能验证词库有没有生效 ----
        val testInput = EditText(ui.activity).apply {
            hint = "随便贴一句英文，例如 Used when the provider is"
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 13f
            setTextColor(Palette.ink)
            setHintTextColor(Palette.muted)
            setBackgroundColor(Palette.surfaceMuted)
            setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
        }
        into.addView(ui.card().apply {
            addView(ui.label("试翻一下", 14, Palette.ink, true))
            addView(ui.label(
                "先在这里试试，能翻出中文就说明词库没问题；翻不出来再加词条。",
                11, Palette.muted, false
            ).apply { setPadding(0, ui.dp(6), 0, 0) })
            addView(testInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        })
        into.addView(ui.actionCard("翻译这段话", "用当前词库翻一次，不涉及目标应用") {
            val source = testInput.text.toString().trim()
            if (source.isEmpty()) {
                host.toast("先贴一句英文")
            } else {
                // 用「内置 + 用户词条」，和 hook 里跑的是同一套逻辑
                val dict = TranslateDict.loadLocal(ui.activity) + entries
                val result = TranslateDict.translate(source, dict)
                ui.dialog(
                    title = "翻译结果",
                    message = if (result == null) {
                        "这句一个词都没命中，所以保持原样。\n\n" +
                            "把里面出现的关键词加到下面「自己加词条」里再试。"
                    } else {
                        result
                    },
                    confirmText = "知道了",
                    cancelText = null
                )
            }
        })

        // ---- 添加词条 ----
        val enInput = EditText(ui.activity).apply {
            hint = "英文，例如 Voucher Center"
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 13f
            setTextColor(Palette.ink)
            setHintTextColor(Palette.muted)
            setBackgroundColor(Palette.surfaceMuted)
            setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
            isSingleLine = true
        }
        val zhInput = EditText(ui.activity).apply {
            hint = "中文，例如 兑换中心"
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 13f
            setTextColor(Palette.ink)
            setHintTextColor(Palette.muted)
            setBackgroundColor(Palette.surfaceMuted)
            setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
            isSingleLine = true
        }
        into.addView(ui.card().apply {
            addView(ui.label("自己加词条", 14, Palette.ink, true))
            addView(enInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
            addView(zhInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
            addView(ui.label("英文不区分大小写，同一个词再加会覆盖旧的。", 10, Palette.muted, false).apply {
                setPadding(0, ui.dp(6), 0, 0)
            })
        })
        into.addView(ui.actionCard("保存词条", "写进目标应用，重启后生效") {
            val en = enInput.text.toString().trim().lowercase()
            val zh = zhInput.text.toString().trim()
            when {
                en.isEmpty() || zh.isEmpty() -> host.toast("英文和中文都要填")
                en.none { it in 'a'..'z' } -> host.toast("英文那栏要填英文字母")
                else -> {
                    val updated = entries.toMutableMap()
                    updated[en] = zh
                    saveEntries(updated, "已添加：$en → $zh", refreshAfter = true)
                }
            }
        })

        // ---- 自定义词条列表 ----
        into.addView(ui.sectionTitle("我加的（${entries.size}）"))
        if (entries.isEmpty()) {
            into.addView(ui.hint("还没加过词条。内置词库已经在工作了，这里只放你自己补的。"))
        } else {
            entries.entries.sortedBy { it.key }.forEach { entry ->
                into.addView(entryCard(entry.key, entry.value, entries))
            }
        }

        // ---- 导入 / 导出 ----
        into.addView(ui.actionCard("导出词条", "导出成文本，方便备份或换手机用") {
            host.exportTranslateDict(pkg, name, TranslateDict.exportText(entries))
        })
        into.addView(ui.actionCard("导入词条", "从文本文件追加，同名词以文件里的为准") {
            host.importTranslateDict(pkg, name)
        })

        into.addView(ui.hint(
            "说明：\n" +
                "1. 改完要重启目标应用才会长期生效；\n" +
                "2. 用输入框打字的地方不会被翻译，避免把你输入的内容改掉；\n" +
                "3. 长句子会拆成一个个词翻，所以结果可能中英混着，这是正常的；\n" +
                "4. 软件自己刷新文字时会重新显示英文，模块会再翻一次，可能有轻微跳动。"
        ))
        into.addView(ui.actionCard("查看日志", "运行记录与错误信息") { host.openLogs(pkg, name, com.example.selfxposed.ConfigClient.F_TRANSLATE) })
    }

    /** 一条自定义词条：长按删除（删除类操作要二次确认）。 */
    private fun entryCard(key: String, value: String, entries: MutableMap<String, String>): View {
        val container = ui.card()
        container.addView(ui.label(key, 13, Palette.ink, true))
        container.addView(ui.label("→ $value", 12, Palette.muted, false).apply {
            setPadding(0, ui.dp(4), 0, 0)
        })
        container.addView(ui.label("长按删除这条", 10, Palette.muted, false).apply {
            setPadding(0, ui.dp(6), 0, 0)
        })
        ui.pressable(container, {}, {
            ui.dialog(
                title = "删除词条",
                message = "确定删除「$key → $value」吗？",
                confirmText = "删除",
                cancelText = "取消",
                onConfirm = {
                    val updated = entries.toMutableMap()
                    updated.remove(key)
                    saveEntries(updated, "已删除", refreshAfter = true)
                }
            )
        })
        return container
    }

    /** 写词条走后台线程（主线程不做 root IO）。 */
    private fun saveEntries(
        entries: Map<String, String>,
        message: String,
        refreshAfter: Boolean = false
    ) {
        val snapshot = HashMap(entries)
        Thread {
            val ok = TranslateDict.writeRemoteUser(pkg, snapshot)
            TargetFiles.invalidate(pkg)
            ui.activity.runOnUiThread {
                host.toast(if (ok) message else "写入失败，检查 Root / Shizuku 授权")
                if (refreshAfter && ok) host.refresh()
            }
        }.start()
    }
}
