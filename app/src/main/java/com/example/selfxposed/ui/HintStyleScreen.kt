package com.example.selfxposed.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.example.selfxposed.ConfigClient
import com.example.selfxposed.HintStyle
import com.example.selfxposed.HintStyleWriter
import com.example.selfxposed.RootShell
import com.example.selfxposed.WorkMode

/**
 * 拦截提示样式：
 * - 预设：存模块配置，通过 Provider 下发，targetSdk < 30 的应用免 root 生效；
 * - 自定义：写进目标应用自己的目录，需要 root 能力（Root 或 Shizuku 模式）。
 */
class HintStyleScreen(
    private val host: ScreenHost,
    private val fromPkg: String? = null
) {
    private val ui get() = host.ui
    private val repo get() = host.repo
    private val canRoot get() = WorkMode.canUseRoot(ui.activity)

    fun render(into: LinearLayout) {
        val current = loadPreset()
        var sizeSp = current.sizeSp
        var position = current.position
        var bgColor = current.bgColor
        val offsetDp = current.offsetDp

        into.addView(ui.backRow("‹  返回") {
            if (fromPkg != null) host.openUpdateBlock(fromPkg, repo.label(fromPkg)) else host.openFeatures()
        })
        into.addView(ui.sectionTitle("拦截提示样式"))

        if (!canRoot) {
            into.addView(ui.hint("当前是非 Root 模式：上面的预设可以直接用，下面的自定义样式需要 root。"))
        }

        // ---- 预览 ----
        val preview = TextView(ui.activity).apply {
            text = "已拦截弹窗 · 长按改规则"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val previewBox = LinearLayout(ui.activity).apply {
            setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
            background = ui.rounded(Palette.surfaceMuted, 14)
            addView(preview)
        }
        into.addView(ui.card().apply {
            addView(ui.label("预览", 13, Palette.muted, true))
            addView(previewBox, LinearLayout.LayoutParams(-1, ui.dp(130)).apply { topMargin = ui.dp(8) })
        })

        fun applyPreview() {
            preview.textSize = sizeSp.toFloat()
            preview.setPadding(ui.dp(14), ui.dp(7), ui.dp(14), ui.dp(7))
            preview.background = GradientDrawable().apply {
                setColor(parseColor(bgColor))
                cornerRadius = ui.dp(16).toFloat()
            }
            previewBox.gravity = when (position) {
                "top" -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                "center" -> Gravity.CENTER
                else -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
        }

        // ---- 预设 ----
        into.addView(ui.sectionTitle("预设"))
        val sizeLabel = ui.label("字号：${sizeText(sizeSp)}", 13, Palette.accent, true)
        into.addView(ui.card().apply {
            addView(sizeLabel)
            addView(optionRow(
                labels = listOf("小", "中", "大"),
                selected = when (sizeSp) {
                    11 -> 0
                    15 -> 2
                    else -> 1
                }
            ) { index ->
                sizeSp = listOf(11, 12, 15)[index]
                sizeLabel.text = "字号：${sizeText(sizeSp)}"
                applyPreview()
            })
        })

        val posLabel = ui.label("位置：${positionText(position)}", 13, Palette.accent, true)
        into.addView(ui.card().apply {
            addView(posLabel)
            addView(optionRow(
                labels = listOf("底部", "中间", "顶部"),
                selected = when (position) {
                    "top" -> 2
                    "center" -> 1
                    else -> 0
                }
            ) { index ->
                position = listOf("bottom", "center", "top")[index]
                posLabel.text = "位置：${positionText(position)}"
                applyPreview()
            })
        })

        val bgLabel = ui.label("背景：${bgText(bgColor)}", 13, Palette.accent, true)
        val bgOptions = listOf("深灰" to "#CC1C1B1F", "纯黑" to "#F01C1B1F", "主题紫" to "#CC6750A4")
        into.addView(ui.card().apply {
            addView(bgLabel)
            addView(optionRow(
                labels = bgOptions.map { it.first },
                selected = bgOptions.indexOfFirst { it.second.equals(bgColor, true) }.coerceAtLeast(0)
            ) { index ->
                bgColor = bgOptions[index].second
                bgLabel.text = "背景：${bgText(bgColor)}"
                applyPreview()
            })
        })

        applyPreview()

        into.addView(ui.actionCard("保存预设", "写入模块配置，启动目标应用后生效") {
            val style = HintStyle(sizeSp, position, offsetDp, bgColor)
            try {
                ConfigClient.saveHintStyle(ui.activity.contentResolver, style.toJson().toString())
            } catch (_: Throwable) {
            }
            if (canRoot && RootShell.hasRootCached()) {
                HintStyleWriter.apply(ui.activity, style, repo.targets())
            }
            host.toast("预设已保存")
        })

        // ---- 自定义 ----
        into.addView(ui.sectionTitle("自定义"))
        if (!canRoot) {
            into.addView(ui.lockedCard("自定义样式", "在设置 → 工作模式切换到 Root / Shizuku 模式后可用"))
            return
        }

        val sizeEdit = EditText(ui.activity).apply {
            hint = "字号，如 12"
            setText(current.sizeSp.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 15f
            setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8))
            background = null
        }
        val offsetEdit = EditText(ui.activity).apply {
            hint = "偏移（dp）"
            setText(current.offsetDp.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 15f
            setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8))
            background = null
        }
        val colorEdit = EditText(ui.activity).apply {
            hint = "#AARRGGBB"
            setText(current.bgColor)
            textSize = 15f
            isSingleLine = true
            setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8))
            background = null
        }
        into.addView(ui.card().apply {
            addView(ui.label("字号（sp）", 13, Palette.muted, true))
            addView(sizeEdit, LinearLayout.LayoutParams(-1, -2))
        })
        into.addView(ui.card().apply {
            addView(ui.label("偏移（dp）", 13, Palette.muted, true))
            addView(offsetEdit, LinearLayout.LayoutParams(-1, -2))
        })
        into.addView(ui.card().apply {
            addView(ui.label("背景颜色", 13, Palette.muted, true))
            addView(colorEdit, LinearLayout.LayoutParams(-1, -2))
            addView(ui.label("支持 #AARRGGBB 或 #RRGGBB", 11, Palette.muted, false).apply {
                setPadding(0, ui.dp(4), 0, 0)
            })
        })
        into.addView(ui.actionCard("保存自定义", "写入目标应用，重启目标应用生效") {
            val style = HintStyle(
                sizeSp = sizeEdit.text.toString().trim().toIntOrNull()?.coerceIn(8, 48) ?: HintStyle.DEFAULT.sizeSp,
                position = position,
                offsetDp = offsetEdit.text.toString().trim().toIntOrNull()?.coerceIn(0, 800) ?: HintStyle.DEFAULT.offsetDp,
                bgColor = colorEdit.text.toString().trim().ifBlank { HintStyle.DEFAULT.bgColor }
            )
            val ok = HintStyleWriter.apply(ui.activity, style, repo.targets())
            host.toast(if (ok) "已保存，重启目标应用生效" else "写入失败，检查 root / Shizuku 授权")
        })
    }

    private fun loadPreset(): HintStyle = try {
        val raw = ConfigClient.hintStyle(ui.activity.contentResolver)
        HintStyle.fromJson(if (raw.isNullOrBlank()) null else org.json.JSONObject(raw)) ?: HintStyle.DEFAULT
    } catch (_: Throwable) {
        HintStyle.DEFAULT
    }

    /** 横向选项行：点击后原地刷新所有按钮的选中态。 */
    private fun optionRow(
        labels: List<String>,
        selected: Int,
        onPick: (Int) -> Unit
    ): LinearLayout {
        val row = LinearLayout(ui.activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, ui.dp(8), 0, 0)
        }
        val buttons = mutableListOf<TextView>()
        fun refresh(chosen: Int) {
            buttons.forEachIndexed { index, button ->
                val active = index == chosen
                button.background = ui.rounded(if (active) Palette.accent else Palette.background, 12)
                button.setTextColor(if (active) Color.WHITE else Palette.ink)
            }
        }
        labels.forEachIndexed { index, label ->
            val button = ui.label(label, 13, Palette.ink, true).apply {
                gravity = Gravity.CENTER
                setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8))
                typeface = Typeface.DEFAULT_BOLD
                isClickable = true
                setOnClickListener {
                    refresh(index)
                    onPick(index)
                }
            }
            buttons.add(button)
            row.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (index > 0) leftMargin = ui.dp(8)
            })
        }
        refresh(selected)
        return row
    }

    private fun parseColor(value: String): Int = try {
        Color.parseColor(value)
    } catch (_: Throwable) {
        Color.argb(215, 28, 27, 31)
    }

    private fun sizeText(sizeSp: Int) = when (sizeSp) {
        11 -> "小"
        15 -> "大"
        else -> "中"
    }

    private fun positionText(position: String) = when (position) {
        "top" -> "顶部"
        "center" -> "中间"
        else -> "底部"
    }

    private fun bgText(color: String) = when (color.uppercase()) {
        "#F01C1B1F" -> "纯黑"
        "#CC6750A4" -> "主题紫"
        else -> "深灰"
    }
}
