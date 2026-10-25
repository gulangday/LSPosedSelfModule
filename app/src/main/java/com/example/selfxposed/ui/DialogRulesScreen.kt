package com.example.selfxposed.ui

import android.widget.LinearLayout
import com.example.selfxposed.DialogRules
import com.example.selfxposed.ShizukuBridge
import com.example.selfxposed.WorkMode

/**
 * 弹窗规则管理：规则存在目标应用自己的数据里（绕开 targetSdk 35 的限制）。
 * 读取/清除需要 root 能力：Root 模式（直接 su）或 Shizuku 模式（Shizuku 以 root 启动）。
 */
class DialogRulesScreen(
    private val host: ScreenHost,
    private val onlyPackage: String? = null
) {
    private val ui get() = host.ui
    private val repo get() = host.repo

    fun render(into: LinearLayout) {
        into.addView(ui.backRow("‹  返回") {
            if (onlyPackage != null) host.openApp(onlyPackage, repo.label(onlyPackage)) else host.openFeatures()
        })
        into.addView(ui.sectionTitle(if (onlyPackage != null) "弹窗规则 · ${repo.label(onlyPackage)}" else "弹窗规则"))
        into.addView(ui.hint("规则是模块在目标应用里学习到的，存在目标应用自己的数据目录，需要 root 能力才能查看和清除。"))

        if (!WorkMode.canUseRoot(ui.activity)) {
            into.addView(ui.card().apply {
                addView(ui.label("当前模式没有 root 能力", 15, Palette.ink, true))
                addView(ui.label("到 设置 → 工作模式 选择 Root 或 Shizuku 模式后再来。", 12, Palette.muted, false).apply {
                    setPadding(0, ui.dp(6), 0, 0)
                })
            })
            into.addView(ui.actionCard("切换工作模式", "Root / Shizuku / 非 Root") { host.openSettings() })
            return
        }

        if (ShizukuBridge.isAlive() && !ShizukuBridge.isBound()) {
            ShizukuBridge.bind(android.content.ComponentName(ui.activity, com.example.selfxposed.RemoteService::class.java))
            into.addView(ui.card().apply {
                addView(ui.label("正在连接 Shizuku…", 15, Palette.ink, true))
                addView(ui.label("首次连接需要几秒，连上后会自动刷新。", 12, Palette.muted, false).apply {
                    setPadding(0, ui.dp(6), 0, 0)
                })
            })
            Thread {
                ShizukuBridge.ensureBound(10_000L)
                ui.activity.runOnUiThread { host.refresh() }
            }.start()
            return
        }

        val targets = onlyPackage?.let { listOf(it) } ?: repo.targets()
        if (targets.isEmpty()) {
            into.addView(ui.actionCard("还没有目标应用", "先回主页添加") { host.openHome() })
            return
        }

        targets.forEachIndexed { index, pkg ->
            val rule = DialogRules.read(pkg)
            val card = ui.card()
            card.addView(ui.label(repo.label(pkg), 15, Palette.ink, true))
            card.addView(ui.label(pkg, 10, Palette.muted, false).apply {
                setPadding(0, ui.dp(2), 0, 0)
            })

            // 图片在上
            addShots(card, pkg)
            // 分割线：上面是界面截图，下面是规则信息
            card.addView(ui.divider())

            // 规则信息在图片下面
            if (rule == null) {
                card.addView(ui.chip("未设置（或读取失败）", Palette.muted, Palette.surfaceMuted))
            } else {
                card.addView(ui.chip(DialogRules.modeLabel(rule.mode), Palette.success, Palette.successSoft))
                if (rule.titles.isNotEmpty()) {
                    card.addView(ui.label("已记录标题：${rule.titles.joinToString("、")}", 11, Palette.muted, false).apply {
                        setPadding(0, ui.dp(6), 0, 0)
                    })
                }
                card.addView(ui.actionCard("清除规则", "下次弹窗会重新询问") {
                    if (DialogRules.clear(pkg)) host.refresh() else host.toast("清除失败，检查 root / Shizuku 授权")
                })
            }
            into.addView(card)
            if (index < targets.lastIndex) into.addView(ui.divider())
        }

        into.addView(ui.actionCard("刷新", "重新读取规则") {
            ShizukuBridge.bind(android.content.ComponentName(ui.activity, com.example.selfxposed.RemoteService::class.java))
            host.refresh()
        })
    }

    /** 拦截截图：显示最近 3 张缩略图，点击看大图。 */
    private fun addShots(card: android.widget.LinearLayout, pkg: String) {
        val shots = loadShots(pkg)
        card.addView(ui.label("拦截截图", 12, Palette.muted, true).apply {
            setPadding(0, ui.dp(10), 0, 0)
        })
        if (shots.isEmpty()) {
            card.addView(ui.label("还没有截图（拦截弹窗后自动保存）", 11, Palette.muted, false).apply {
                setPadding(0, ui.dp(4), 0, 0)
            })
            return
        }
        val row = android.widget.LinearLayout(ui.activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, ui.dp(6), 0, 0)
        }
        shots.forEach { bitmap ->
            val image = android.widget.ImageView(ui.activity).apply {
                setImageBitmap(bitmap)
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                background = ui.rounded(Palette.surfaceMuted, 10)
                setPadding(2, 2, 2, 2)
                isClickable = true
                setOnClickListener { showLarge(bitmap) }
            }
            row.addView(image, android.widget.LinearLayout.LayoutParams(0, ui.dp(96), 1f).apply {
                rightMargin = ui.dp(8)
            })
        }
        card.addView(row)
    }

    private fun loadShots(pkg: String, limit: Int = 3): List<android.graphics.Bitmap> {
        return try {
            val dir = "/data/data/$pkg/files/blocked"
            val listing = com.example.selfxposed.RootShell.exec("ls -1t $dir 2>/dev/null | head -$limit")
            if (listing.isBlank()) return emptyList()
            listing.lines()
                .map { it.trim() }
                .filter { it.endsWith(".png") }
                .mapNotNull { name ->
                    val raw = com.example.selfxposed.RootShell.exec("base64 -w0 $dir/$name 2>/dev/null")
                    if (raw.isBlank()) {
                        null
                    } else {
                        try {
                            val bytes = android.util.Base64.decode(raw.trim(), android.util.Base64.DEFAULT)
                            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        } catch (_: Throwable) {
                            null
                        }
                    }
                }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun showLarge(bitmap: android.graphics.Bitmap) {
        try {
            val dialog = android.app.Dialog(ui.activity, android.R.style.Theme_Translucent_NoTitleBar)
            val image = android.widget.ImageView(ui.activity).apply {
                setImageBitmap(bitmap)
                adjustViewBounds = true
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                setOnClickListener { dialog.dismiss() }
            }
            val wrapper = android.widget.FrameLayout(ui.activity).apply {
                setBackgroundColor(android.graphics.Color.argb(220, 0, 0, 0))
                setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))
                addView(image, android.widget.FrameLayout.LayoutParams(-1, -1))
            }
            dialog.setContentView(wrapper)
            dialog.window?.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            )
            dialog.show()
        } catch (_: Throwable) {
        }
    }
}
