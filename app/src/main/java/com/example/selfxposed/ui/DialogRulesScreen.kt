package com.example.selfxposed.ui

import android.graphics.Bitmap
import android.widget.ImageView
import android.widget.LinearLayout
import com.example.selfxposed.DialogRules
import com.example.selfxposed.RootShell
import com.example.selfxposed.ShizukuBridge
import com.example.selfxposed.WorkMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 弹窗规则管理：规则存在目标应用自己的数据里（绕开 targetSdk 35 的限制）。
 * 每条拦截记录 = 一张弹窗截图 + 标题 / 时间，方便回溯拦了哪些弹窗。
 */
class DialogRulesScreen(
    private val host: ScreenHost,
    private val onlyPackage: String? = null
) {
    private val ui get() = host.ui
    private val repo get() = host.repo

    private data class ShotRecord(
        val bitmap: Bitmap,
        val title: String,
        val timeText: String
    )

    fun render(into: LinearLayout) {
        into.addView(ui.backRow("‹  返回") { host.backFromRules() })
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

            if (rule == null) {
                card.addView(ui.chip("未设置（或读取失败）", Palette.muted, Palette.surfaceMuted))
            } else {
                card.addView(ui.chip(DialogRules.modeLabel(rule.mode), Palette.success, Palette.successSoft))
                if (rule.titles.isNotEmpty()) {
                    card.addView(ui.label("已记录标题：${rule.titles.joinToString("、")}", 11, Palette.muted, false).apply {
                        setPadding(0, ui.dp(6), 0, 0)
                    })
                }
            }

            card.addView(ui.divider())
            addRecords(card, pkg)
            into.addView(card)
            if (index < targets.lastIndex) into.addView(ui.divider())
        }

        // 与「刷新」同级：整页操作
        into.addView(ui.actionCard("刷新", "重新读取规则") {
            ShizukuBridge.bind(android.content.ComponentName(ui.activity, com.example.selfxposed.RemoteService::class.java))
            host.refresh()
        })
        into.addView(ui.actionCard("清除规则", "删除这些规则和截图，下次弹窗重新询问") {
            ui.dialog(
                title = "清除规则",
                message = "将删除这些应用学到的全部规则和拦截截图，下次弹窗会重新询问。",
                confirmText = "清除",
                cancelText = "取消",
                onConfirm = {
                    var ok = true
                    targets.forEach { pkg -> if (!DialogRules.clear(pkg)) ok = false }
                    if (ok) {
                        host.toast("已清除")
                        host.refresh()
                    } else {
                        host.toast("清除失败，检查 Root / Shizuku 授权")
                    }
                }
            )
        })
    }

    /** 拦截记录：每条 = 缩略图 + 标题 + 时间，点击看大图。 */
    private fun addRecords(card: LinearLayout, pkg: String) {
        card.addView(ui.label("拦截记录", 12, Palette.muted, true))
        val records = loadRecords(pkg)
        if (records.isEmpty()) {
            card.addView(ui.label("还没有记录（拦截弹窗后自动保存）", 11, Palette.muted, false).apply {
                setPadding(0, ui.dp(4), 0, 0)
            })
            return
        }
        records.forEach { record ->
            val row = LinearLayout(ui.activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, ui.dp(8), 0, 0)
            }
            val image = ImageView(ui.activity).apply {
                setImageBitmap(record.bitmap)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = ui.rounded(Palette.surfaceMuted, 8)
                isClickable = true
                setOnClickListener { showLarge(record.bitmap) }
            }
            row.addView(image, LinearLayout.LayoutParams(ui.dp(72), ui.dp(72)))
            val info = LinearLayout(ui.activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(12), 0, 0, 0)
            }
            info.addView(ui.label(record.title.ifBlank { "弹窗截图" }, 13, Palette.ink, true))
            info.addView(ui.label(record.timeText, 11, Palette.muted, false).apply {
                setPadding(0, ui.dp(4), 0, 0)
            })
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            ui.pressable(row, { showLarge(record.bitmap) })
            card.addView(row)
        }
    }

    private fun loadRecords(pkg: String, limit: Int = 10): List<ShotRecord> {
        return try {
            val dir = "/data/data/$pkg/files/blocked"
            val listing = RootShell.exec("ls -1t $dir 2>/dev/null | head -$limit")
            if (listing.isBlank()) return emptyList()
            listing.lines()
                .map { it.trim() }
                .filter { it.endsWith(".png") }
                .mapNotNull { name ->
                    val raw = RootShell.exec("base64 -w0 $dir/$name 2>/dev/null")
                    if (raw.isBlank()) {
                        return@mapNotNull null
                    }
                    val bitmap = try {
                        val bytes = android.util.Base64.decode(raw.trim(), android.util.Base64.DEFAULT)
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } catch (_: Throwable) {
                        null
                    } ?: return@mapNotNull null
                    val title = RootShell.exec(
                        "cat $dir/${name.removeSuffix(".png")}.txt 2>/dev/null"
                    ).trim()
                    val stamp = name.substringBefore(".png").toLongOrNull() ?: 0L
                    val timeText = if (stamp > 0) {
                        SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(stamp))
                    } else {
                        ""
                    }
                    ShotRecord(bitmap, title, timeText)
                }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun showLarge(bitmap: Bitmap) {
        try {
            val dialog = android.app.Dialog(ui.activity, android.R.style.Theme_Translucent_NoTitleBar)
            val image = ImageView(ui.activity).apply {
                setImageBitmap(bitmap)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
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
