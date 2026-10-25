package com.example.selfxposed.ui

import android.widget.LinearLayout

/** 使用说明：解释作用域、开关、弹窗三者的关系。 */
class HelpScreen(private val host: ScreenHost) {
    private val ui get() = host.ui

    fun render(into: LinearLayout) {
        into.addView(ui.backRow("‹  返回") { host.openHome() })
        into.addView(ui.sectionTitle("使用说明"))

        into.addView(ui.card().apply {
            addView(
                ui.label(
                    "1. 安装 APK 后，在 LSPosed 管理器中启用「自用模块」。\n\n" +
                        "2. 打开模块主页 →「添加目标应用」，选择需要弹窗的应用。\n\n" +
                        "3. 在 LSPosed 的「作用域」里勾选同一个应用（添加应用不会自动勾选作用域）。\n\n" +
                        "4. 启动一次该应用：模块会记录一次 Hook 握手，主页状态变成「Hook 已连接」。\n\n" +
                        "5. 回到模块，打开该应用的开关，设置弹窗文字、显示时长与淡出时长。\n\n" +
                        "6. 再次切到该应用，弹窗会在底部出现。",
                    14, Palette.ink, false
                )
            )
        })

        into.addView(ui.card().apply {
            addView(ui.label("关于弹窗", 15, Palette.ink, true))
            addView(ui.label(
                "· 白底黑字、底部居中，由模块自绘，不走系统 Toast；\n" +
                    "· 显示时长默认 5 秒，淡出时长默认 1 秒，都可以在应用设置里调整；\n" +
                    "· 在同一应用内切换页面不会重新计时，弹窗会跟着新界面继续显示剩余时间；\n" +
                    "· 应用设置里的「预览弹窗」可以直接在模块界面看到效果。",
                13, Palette.muted, false
            ).apply { setPadding(0, ui.dp(8), 0, 0) })
        })

        into.addView(ui.card().apply {
            addView(ui.label("关于状态与作用域", 15, Palette.ink, true))
            addView(ui.label(
                "· 卡片上的开关是模块自己的配置，LSPosed 作用域是系统的注入范围，两者必须同时打开；\n" +
                    "· 显示「待检测」通常意味着作用域没有勾选，或添加应用后还没启动过它；\n" +
                    "· 长按应用卡片可以移除目标应用，移除时会自动关闭它的开关。",
                13, Palette.muted, false
            ).apply { setPadding(0, ui.dp(8), 0, 0) })
        })

        into.addView(ui.card().apply {
            addView(ui.label("关于后台与自启动", 15, Palette.ink, true))
            addView(ui.label(
                "· 目标应用读取配置时需要唤起本模块，模块被系统清掉后，只有「自启动」权限才能让它被唤醒；\n" +
                    "· 在「设置 → 后台与自启动」里打开「后台常驻」，再到系统的自启动管理里允许本模块；\n" +
                    "· 两项都开启后，即使把模块从最近任务里划掉，目标应用依然能正常弹出提示。",
                13, Palette.muted, false
            ).apply { setPadding(0, ui.dp(8), 0, 0) })
        })
    }
}
