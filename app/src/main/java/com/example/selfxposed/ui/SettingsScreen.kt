package com.example.selfxposed.ui

import android.widget.LinearLayout
import com.example.selfxposed.RootShell
import com.example.selfxposed.ShizukuBridge
import com.example.selfxposed.WorkMode

/** 设置页：只放入口，具体内容进去看。 */
class SettingsScreen(private val host: ScreenHost) {
    private val ui get() = host.ui
    private val repo get() = host.repo

    fun render(into: LinearLayout) {
        into.addView(ui.sectionTitle("设置"))
        into.addView(ui.entryRow("工作模式", WorkMode.label(WorkMode.get(ui.activity))) { pickWorkMode() })
        into.addView(ui.hint(
            "Root 模式和 Shizuku 模式能做的事一样，一般用 Shizuku 就够。\n" +
                "如果一定要用 Root 模式，需要满足其中一个条件：\n" +
                "· 关闭隐藏 root 的模块（如 Shamiko / DenyList）\n" +
                "· 或让 Shizuku 以 root 运行后，从这里直接切到 Root 模式"
        ))
        into.addView(ui.entryRow("权限", "后台常驻、自启动") { host.openPermissions() })
        into.addView(ui.entryRow("实时日志", "查看目标应用的运行记录") { pickLogs() })
        into.addView(ui.entryRow("关于", "版本、包名与项目地址") { host.openAbout() })
    }

    private fun pickWorkMode() {
        val current = WorkMode.get(ui.activity)
        ui.listDialog(
            title = "工作模式（当前：${WorkMode.label(current)}）",
            items = listOf(
                "Root 模式：直接请求 root",
                "Shizuku 模式：通过 Shizuku 执行命令",
                "非 Root 模式：不需要任何权限"
            ),
            selectedIndex = when (current) {
                WorkMode.ROOT -> 0
                WorkMode.SHIZUKU -> 1
                WorkMode.NO_ROOT -> 2
                else -> -1
            },
            onPick = { which ->
                android.util.Log.i("SelfXposedRoot", "pickWorkMode which=$which current=${WorkMode.get(ui.activity)}")
                when (which) {
                    0 -> {
                        host.toast("正在请求 root 权限…")
                        ShizukuBridge.bind(android.content.ComponentName(ui.activity, com.example.selfxposed.RemoteService::class.java))
                        Thread {
                            Thread.sleep(1200)
                            val granted = RootShell.hasRoot()
                            val hasSu = if (granted) true else RootShell.suExists()
                            ui.activity.runOnUiThread {
                                if (granted) {
                                    WorkMode.set(ui.activity, WorkMode.ROOT)
                                    host.toast("已切换为 Root 模式")
                                    host.refresh()
                                } else {
                                    ui.dialog(
                                        title = "未获得 root 权限",
                                        message = if (hasSu) {
                                            "设备上有 su，但请求被拒绝了。\n\n" +
                                                "可以到 Magisk → 超级用户 里允许「自用模块」，" +
                                                "或者改用 Shizuku 模式。"
                                        } else {
                                            "设备上没有可供应用调用的 su（可能被隐藏 root 的模块挡住了）。\n\n" +
                                                "建议改用 Shizuku 模式。"
                                        },
                                        confirmText = "知道了",
                                        cancelText = null
                                    )
                                }
                            }
                        }.start()
                    }

                    1 -> {
                        if (!ShizukuBridge.isAlive()) {
                            host.toast("Shizuku 没有在运行")
                            return@listDialog
                        }
                        ShizukuBridge.requestPermission()
                        ShizukuBridge.bind(android.content.ComponentName(ui.activity, com.example.selfxposed.RemoteService::class.java))
                        ui.activity.runOnUiThread {
                            if (ShizukuBridge.hasPermission()) {
                                WorkMode.set(ui.activity, WorkMode.SHIZUKU)
                                host.toast("已切换为 Shizuku 模式")
                            } else {
                                host.toast("请先在 Shizuku 弹窗里允许")
                            }
                            host.refresh()
                        }
                    }

                    else -> {
                        WorkMode.set(ui.activity, WorkMode.NO_ROOT)
                        android.util.Log.i(
                            "SelfXposedRoot",
                            "切换非 Root 后读取=${WorkMode.get(ui.activity)}"
                        )
                        host.toast("已切换为非 Root 模式")
                        host.refresh()
                    }
                }
            }
        )
    }

    private fun openMagisk() {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .setPackage("com.topjohnwu.magisk")
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            ui.activity.startActivity(intent)
        } catch (_: Throwable) {
            host.toast("没有找到 Magisk")
        }
    }

    private fun pickLogs() {
        val targets = repo.targets()
        if (targets.isEmpty()) {
            host.toast("当前没有目标应用")
            return
        }
        val names = targets.map { repo.label(it) }
        ui.listDialog(
            title = "选择应用日志",
            items = names,
            onPick = { which -> host.openLogs(targets[which], names[which]) }
        )
    }
}
