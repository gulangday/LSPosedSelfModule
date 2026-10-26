package com.example.selfxposed.ui

import android.text.InputType
import android.view.Gravity
import android.widget.LinearLayout
import com.example.selfxposed.AdSdkBlacklist
import com.example.selfxposed.AdUrlBlacklist
import com.example.selfxposed.Features
import com.example.selfxposed.TargetFiles
import com.example.selfxposed.WorkMode

/**
 * 去广告页：一个开关控制全部（开屏 / 摇一摇 / 广告落地页）。
 * 弹窗式广告交给「拦截弹窗」的关键字/规则处理。
 */
class AdBlockScreen(
    private val host: ScreenHost,
    private val pkg: String,
    private val name: String
) {
    private val ui get() = host.ui
    private val repo get() = host.repo

    fun render(into: LinearLayout) {
        into.addView(ui.backRow("‹  功能") { host.openFeatures() })
        into.addView(ui.sectionTitle("去广告 · $name"))
        into.addView(ui.label(pkg, 12, Palette.muted, false).apply { setPadding(ui.dp(2), 0, 0, 0) })

        val canRoot = WorkMode.canUseRoot(ui.activity)
        val state = if (canRoot) TargetFiles.load(pkg) else TargetFiles.TargetState()
        val masterOn = canRoot && state.isOn(Features.MASTER)

        if (!masterOn) {
            into.addView(ui.card(Palette.hero).apply {
                elevation = 0f
                addView(ui.label("模块未启用，去广告不会生效", 15, Palette.ink, true))
                addView(ui.label(
                    "先在主页把该应用的「模块已启用」打开。",
                    12, Palette.muted, false
                ).apply { setPadding(0, ui.dp(8), 0, 0) })
            })
            into.addView(ui.lockedCard("去广告", "开屏 / 摇一摇 / 广告落地页", tag = "模块未启用"))
            into.addView(ui.actionCard("查看日志", "运行记录与错误信息") { host.openLogs(pkg, name) })
            return
        }

        val adChip = ui.statusChip(state.isOn(Features.AD)) { checked ->
            val ok = Features.setRemote(pkg, Features.AD, checked)
            TargetFiles.invalidate(pkg)
            host.toast(if (ok) "已保存，重启目标应用生效" else "写入失败，检查 Root / Shizuku 授权")
            ok
        }
        into.addView(ui.card().apply {
            val row = LinearLayout(ui.activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(
                ui.label("去广告", 15, Palette.ink, true),
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            row.addView(adChip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(2) })
            addView(row)
            addView(ui.label(
                "一个开关控制全部：开屏广告、摇一摇跳转、广告落地页。\n" +
                    "弹窗式广告请用「拦截弹窗」按关键字拦。",
                11, Palette.muted, false
            ).apply { setPadding(0, ui.dp(6), 0, 0) })
        })

        // 调试：记录 DNS 请求域名，方便找出广告域名
        val logChip = ui.statusChip(state.isOn(Features.AD_LOG)) { checked ->
            val ok = Features.setRemote(pkg, Features.AD_LOG, checked)
            TargetFiles.invalidate(pkg)
            host.toast(if (ok) "已保存，重启目标应用生效" else "写入失败，检查 Root / Shizuku 授权")
            ok
        }
        into.addView(ui.card().apply {
            val row = LinearLayout(ui.activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(
                ui.label("记录请求域名（调试）", 14, Palette.ink, true),
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            row.addView(logChip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(2) })
            addView(row)
            addView(ui.label(
                "打开后把目标应用的 DNS 请求域名写进日志，用来找出广告域名再加进黑名单。\n" +
                    "平时可以关掉，避免日志太多。",
                11, Palette.muted, false
            ).apply { setPadding(0, ui.dp(6), 0, 0) })
        })

        // ---- SDK 黑名单（可编辑）----
        val currentList = if (state.adSdkLines.isEmpty()) AdSdkBlacklist.DEFAULT_PREFIXES else state.adSdkLines
        val sdkEdit = android.widget.EditText(ui.activity).apply {
            setText(currentList.joinToString("\n"))
            textSize = 13f
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(0, ui.dp(6), 0, ui.dp(6))
            isVerticalScrollBarEnabled = true
            scrollBarStyle = android.view.View.SCROLLBARS_INSIDE_INSET
            hint = "每行一个包名前缀，例如：\ncom.bytedance.sdk.openadsdk"
        }
        into.addView(ui.card().apply {
            addView(ui.label("广告 SDK 黑名单（可编辑）", 13, Palette.ink, true))
            addView(sdkEdit, LinearLayout.LayoutParams(-1, ui.dp(140)))
            addView(ui.label(
                "每行一个类名前缀。留空或保存时清空 = 恢复内置默认。",
                11, Palette.muted, false
            ).apply { setPadding(0, ui.dp(4), 0, 0) })
        })
        into.addView(ui.actionCard("保存黑名单", "只影响当前应用，重启后生效") {
            val lines = sdkEdit.text.toString()
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (lines.isEmpty()) {
                // 清空 = 恢复默认：直接删掉配置文件
                val out = com.example.selfxposed.RootShell.exec(
                    "rm -f /data/data/$pkg/files/selfxposed_ad_sdk.conf; echo ok"
                )
                com.example.selfxposed.AdSdkBlacklist.invalidate()
                com.example.selfxposed.TargetFiles.invalidate(pkg)
                host.toast(if (out.contains("ok")) "已恢复内置默认" else "操作失败，检查 Root / Shizuku 授权")
                return@actionCard
            }
            val body = lines.joinToString("\\n")
            val command = "mkdir -p /data/data/$pkg/files; " +
                "printf '%b\\n' '$body' > /data/data/$pkg/files/selfxposed_ad_sdk.conf; " +
                "chown \$(stat -c %u:%g /data/data/$pkg) /data/data/$pkg/files/selfxposed_ad_sdk.conf; " +
                "chmod 660 /data/data/$pkg/files/selfxposed_ad_sdk.conf; echo ok"
            val out = com.example.selfxposed.RootShell.exec(command)
            com.example.selfxposed.AdSdkBlacklist.invalidate()
            com.example.selfxposed.TargetFiles.invalidate(pkg)
            host.toast(if (out.contains("ok")) "黑名单已保存，重启目标应用生效" else "写入失败，检查 Root / Shizuku 授权")
        })

        // ---- 广告域名黑名单（可编辑，不依赖 SDK 类名）----
        val currentUrls = if (state.adUrlLines.isEmpty()) AdUrlBlacklist.DEFAULT_HOSTS else state.adUrlLines
        val urlEdit = android.widget.EditText(ui.activity).apply {
            setText(currentUrls.joinToString("\n"))
            textSize = 13f
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(0, ui.dp(6), 0, ui.dp(6))
            isVerticalScrollBarEnabled = true
            scrollBarStyle = android.view.View.SCROLLBARS_INSIDE_INSET
            hint = "每行一个广告域名，例如：\ndoubleclick.net"
        }
        into.addView(ui.card().apply {
            addView(ui.label("广告域名黑名单（可编辑）", 13, Palette.ink, true))
            addView(urlEdit, LinearLayout.LayoutParams(-1, ui.dp(140)))
            addView(ui.label(
                "命中域名的网络请求会被拦掉，不依赖 SDK 类名，混淆也有效。留空保存 = 恢复默认。",
                11, Palette.muted, false
            ).apply { setPadding(0, ui.dp(4), 0, 0) })
        })
        into.addView(ui.actionCard("保存域名黑名单", "只影响当前应用，重启后生效") {
            val lines = urlEdit.text.toString()
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (lines.isEmpty()) {
                val out = com.example.selfxposed.RootShell.exec(
                    "rm -f /data/data/$pkg/files/selfxposed_ad_urls.conf; echo ok"
                )
                com.example.selfxposed.AdUrlBlacklist.invalidate()
                com.example.selfxposed.TargetFiles.invalidate(pkg)
                host.toast(if (out.contains("ok")) "已恢复内置默认" else "操作失败，检查 Root / Shizuku 授权")
                return@actionCard
            }
            val body = lines.joinToString("\\n")
            val command = "mkdir -p /data/data/$pkg/files; " +
                "printf '%b\\n' '$body' > /data/data/$pkg/files/selfxposed_ad_urls.conf; " +
                "chown \$(stat -c %u:%g /data/data/$pkg) /data/data/$pkg/files/selfxposed_ad_urls.conf; " +
                "chmod 660 /data/data/$pkg/files/selfxposed_ad_urls.conf; echo ok"
            val out = com.example.selfxposed.RootShell.exec(command)
            com.example.selfxposed.AdUrlBlacklist.invalidate()
            com.example.selfxposed.TargetFiles.invalidate(pkg)
            host.toast(if (out.contains("ok")) "域名黑名单已保存，重启目标应用生效" else "写入失败，检查 Root / Shizuku 授权")
        })

        into.addView(ui.hint(
            "限制：只能拦 Java 层广告。Unity / Flutter 自绘广告、native 层摇一摇拦不到；\n" +
                "广告 SDK 混淆后类名变化也可能失效。"
        ))
        into.addView(ui.actionCard("查看日志", "运行记录与错误信息") { host.openLogs(pkg, name) })
    }
}
