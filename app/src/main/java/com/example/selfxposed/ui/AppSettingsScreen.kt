package com.example.selfxposed.ui

import android.app.AlertDialog
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.example.selfxposed.PopupController
import java.util.Locale

/** 单个目标应用的配置页：文字、开关、显示/淡出时长、预览与保存。 */
class AppSettingsScreen(
    private val host: ScreenHost,
    private val pkg: String,
    private val name: String
) {
    private val ui get() = host.ui
    private val repo get() = host.repo

    fun render(into: LinearLayout) {
        val cfg = repo.config(pkg)

        into.addView(ui.backRow("‹  返回主页") { host.openHome() })
        into.addView(ui.sectionTitle(name))
        into.addView(ui.label(pkg, 12, Palette.muted, false).apply { setPadding(ui.dp(2), 0, 0, 0) })
        into.addView(ui.hint("修改后点「保存设置」；预览不会写入配置。"))

        val popupSwitch = Switch(ui.activity).apply {
            text = "启用提示通知"
            textSize = 14f
            isChecked = cfg.optBoolean("popupEnabled", true)
            setPadding(0, ui.dp(4), 0, ui.dp(4))
        }
        into.addView(ui.card().apply { addView(popupSwitch) })

        val textEdit = EditText(ui.activity).apply {
            hint = "弹窗文字"
            setText(cfg.optString("popupText", DEFAULT_TEXT))
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            isSingleLine = false
            minLines = 2
            setPadding(0, ui.dp(8), 0, 0)
            background = null
        }
        into.addView(ui.card().apply {
            addView(ui.label("弹窗文字", 13, Palette.muted, true))
            addView(textEdit, LinearLayout.LayoutParams(-1, -2))
        })

        var showMs = cfg.optInt("showMs", DEFAULT_SHOW_MS).coerceIn(SHOW_MIN_MS, SHOW_MAX_MS)
        var fadeMs = cfg.optInt("fadeMs", DEFAULT_FADE_MS).coerceIn(0, FADE_MAX_MS)

        val showValue = ui.label(durationText("显示", showMs), 13, Palette.accent, true)
        val showBar = ui.slider(
            (SHOW_MAX_MS - SHOW_MIN_MS) / SHOW_STEP_MS,
            (showMs - SHOW_MIN_MS) / SHOW_STEP_MS
        ) { value ->
            showMs = SHOW_MIN_MS + value * SHOW_STEP_MS
            showValue.text = durationText("显示", showMs)
        }
        into.addView(ui.card().apply {
            addView(valueRow(ui.label("显示时长", 14, Palette.ink, true), showValue))
            addView(showBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
        })

        val fadeValue = ui.label(durationText("淡出", fadeMs), 13, Palette.accent, true)
        val fadeBar = ui.slider(FADE_MAX_MS / FADE_STEP_MS, fadeMs / FADE_STEP_MS) { value ->
            fadeMs = value * FADE_STEP_MS
            fadeValue.text = durationText("淡出", fadeMs)
        }
        into.addView(ui.card().apply {
            addView(valueRow(ui.label("淡出时长", 14, Palette.ink, true), fadeValue))
            addView(fadeBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
        })

        into.addView(ui.actionCard("预览弹窗", "按当前输入在本页面预览一次") {
            val text = textEdit.text.toString().ifBlank { DEFAULT_TEXT }
            PopupController.show(ui.activity, PREVIEW_KEY, text, showMs, fadeMs, restart = true)
        })

        into.addView(ui.actionCard("保存设置", "只影响 $name") {
            val text = textEdit.text.toString().ifBlank { DEFAULT_TEXT }
            cfg.put("popupEnabled", popupSwitch.isChecked)
            cfg.put("popupText", text)
            cfg.put("showMs", showMs)
            cfg.put("fadeMs", fadeMs)
            repo.saveConfig(pkg, cfg)
            repo.log(pkg, "设置已保存：显示 ${showMs}ms，淡出 ${fadeMs}ms")
            host.toast("已保存")
        })

        into.addView(ui.actionCard("查看日志", "运行记录与错误信息") { host.openLogs(pkg, name) })
        into.addView(ui.actionCard("移除目标应用", "从主页移除，并关闭它的开关") { confirmRemove() })
    }

    private fun valueRow(left: TextView, right: TextView): LinearLayout =
        LinearLayout(ui.activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(left, LinearLayout.LayoutParams(0, -2, 1f))
            addView(right)
        }

    private fun durationText(prefix: String, ms: Int): String =
        if (ms <= 0) "无淡出" else String.format(Locale.US, "%s %.1f 秒", prefix, ms / 1000f)

    private fun confirmRemove() {
        ui.dialog(
            title = "移除目标应用",
            message = "将从主页移除「$name」并关闭它的模块开关，已有日志会保留。",
            confirmText = "移除",
            cancelText = "取消"
        ) {
            repo.removeTarget(pkg)
            host.openHome()
        }
    }

    companion object {
        private const val DEFAULT_TEXT = "你好"
        private const val DEFAULT_SHOW_MS = 5000
        private const val DEFAULT_FADE_MS = 1000
        private const val SHOW_MIN_MS = 500
        private const val SHOW_MAX_MS = 10_000
        private const val SHOW_STEP_MS = 500
        private const val FADE_MAX_MS = 3_000
        private const val FADE_STEP_MS = 100
        private const val PREVIEW_KEY = "preview"
    }
}
