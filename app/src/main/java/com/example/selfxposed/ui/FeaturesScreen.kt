package com.example.selfxposed.ui

import android.widget.LinearLayout

/** 功能页：按模块卡片列出要实现的增强功能。 */
class FeaturesScreen(private val host: ScreenHost) {
    private val ui get() = host.ui

    fun render(into: LinearLayout) {
        into.addView(ui.sectionTitle("功能"))
        into.addView(ui.hint("针对目标应用的增强功能，后续逐个实现。"))

        into.addView(featureCard(
            title = "去更新",
            subtitle = "屏蔽目标应用的更新提示，并可伪装版本号",
            status = "规划中",
            statusColor = Palette.warning,
            statusBackground = Palette.warningSoft
        ))
    }

    private fun featureCard(
        title: String,
        subtitle: String,
        status: String,
        statusColor: Int,
        statusBackground: Int
    ) = ui.card().apply {
        addView(ui.label(title, 16, Palette.ink, true))
        addView(ui.label(subtitle, 12, Palette.muted, false).apply {
            setPadding(0, ui.dp(6), 0, 0)
        })
        addView(ui.chip(status, statusColor, statusBackground))
        ui.pressable(this) {
            ui.dialog(
                title = "去更新",
                message = "计划实现：拦截目标应用的版本检查接口，伪装 versionName / versionCode，" +
                    "并屏蔽「发现新版本」的弹窗与红点提示。\n\n当前版本还没接入，先占位。",
                confirmText = "知道了",
                cancelText = null
            )
        }
    }
}
