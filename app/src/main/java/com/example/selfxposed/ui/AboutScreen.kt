package com.example.selfxposed.ui

import android.content.Intent
import android.net.Uri
import android.widget.LinearLayout

/** 关于页：版本、包名、目标数量与项目地址。 */
class AboutScreen(private val host: ScreenHost) {
    private val ui get() = host.ui

    fun render(into: LinearLayout) {
        into.addView(ui.backRow("‹  设置") { host.openSettings() })
        into.addView(ui.sectionTitle("关于"))

        into.addView(ui.card().apply {
            addView(ui.label("自用模块", 18, Palette.ink, true))
            addView(ui.label("内测版 · ${host.versionName()}", 12, Palette.accent, true).apply {
                setPadding(0, ui.dp(6), 0, 0)
            })
            addView(ui.label(
                "按应用配置的自定义弹窗模块，仅对 LSPosed 作用域内已启用的应用生效。",
                12, Palette.muted, false
            ).apply { setPadding(0, ui.dp(8), 0, 0) })
        })

        into.addView(ui.card().apply {
            addView(infoRow("版本", host.versionName()))
            addView(infoRow("版本号", host.versionCode().toString()))
            addView(infoRow("包名", ui.activity.packageName))
            addView(infoRow("目标应用", "${host.repo.targets().size} 个"))
            addView(infoRow("开发者", "DAY（开发者） · DSH（AI 工具）"))
        })

        into.addView(ui.entryRow("项目地址", "github.com/gulangday/LSPosedSelfModule") { openGithub() })
    }

    private fun infoRow(key: String, value: String) = LinearLayout(ui.activity).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, ui.dp(6), 0, ui.dp(6))
        addView(ui.label(key, 13, Palette.muted, false), LinearLayout.LayoutParams(0, -2, 1f))
        addView(ui.label(value, 13, Palette.ink, false))
    }

    private fun openGithub() {
        try {
            ui.activity.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/gulangday/LSPosedSelfModule"))
            )
        } catch (_: Throwable) {
            host.toast("无法打开浏览器")
        }
    }
}
