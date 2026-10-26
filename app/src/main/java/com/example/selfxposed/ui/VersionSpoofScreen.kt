package com.example.selfxposed.ui

import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import com.example.selfxposed.Features
import com.example.selfxposed.RootShell
import com.example.selfxposed.TargetFiles
import com.example.selfxposed.WorkMode

/**
 * 版本伪装页：让目标应用查到的"自己"是更高的版本，从而不再弹强制更新。
 *
 * 配置写到目标应用本地 `files/selfxposed_update_block.conf`，
 * 只影响「查询本应用包」的返回值，不改系统里真实的包信息。
 */
class VersionSpoofScreen(
    private val host: ScreenHost,
    private val pkg: String,
    private val name: String
) {
    private val ui get() = host.ui
    private val repo get() = host.repo

    fun render(into: LinearLayout) {
        into.addView(ui.backRow("‹  功能") { host.openFeatures() })
        into.addView(ui.sectionTitle("版本伪装 · $name"))
        into.addView(ui.label(pkg, 12, Palette.muted, false).apply { setPadding(ui.dp(2), 0, 0, 0) })

        val canRoot = WorkMode.canUseRoot(ui.activity)
        val state = if (canRoot) TargetFiles.load(pkg) else TargetFiles.TargetState()
        val masterOn = canRoot && state.isOn(Features.MASTER)

        if (!masterOn) {
            into.addView(ui.card(Palette.hero).apply {
                elevation = 0f
                addView(ui.label("模块未启用，版本伪装不会生效", 15, Palette.ink, true))
                addView(ui.label(
                    "先在主页把该应用的「模块已启用」打开。\n" +
                        "已填写的伪装值会保留，启用后立即生效。",
                    12, Palette.muted, false
                ).apply { setPadding(0, ui.dp(8), 0, 0) })
            })
            into.addView(ui.lockedCard("版本伪装", "填一个更高的版本号", tag = "模块未启用"))
            into.addView(ui.actionCard("查看日志", "运行记录与错误信息") { host.openLogs(pkg, name) })
            return
        }

        val realVersion = repo.versionName(pkg) ?: "未知"
        val updateChip = ui.statusChip(state.isOn(Features.UPDATE)) { checked ->
            val ok = Features.setRemote(pkg, Features.UPDATE, checked)
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
                ui.label("版本伪装", 15, Palette.ink, true),
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            row.addView(updateChip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(2) })
            addView(row)
            addView(ui.label(
                "当前版本 $realVersion。填一个比它更高的版本号，目标应用会认为自己已是最新，不再弹更新。",
                11, Palette.muted, false
            ).apply { setPadding(0, ui.dp(6), 0, 0) })
        })

        val fakeNameEdit = EditText(ui.activity).apply {
            hint = "伪装版本名，例如 9.9.9（可留空）"
            setText(state.fakeName)
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(0, ui.dp(8), 0, ui.dp(8))
        }
        val fakeCodeEdit = EditText(ui.activity).apply {
            hint = "伪装版本号，例如 9999999（可留空）"
            setText(if (state.fakeCode > 0) state.fakeCode.toString() else "")
            textSize = 15f
            inputType = InputType.TYPE_CLASS_NUMBER
            setPadding(0, ui.dp(8), 0, ui.dp(8))
        }
        into.addView(ui.card().apply {
            addView(ui.label("伪装版本名", 12, Palette.muted, true))
            addView(fakeNameEdit, LinearLayout.LayoutParams(-1, -2))
            addView(ui.label("伪装版本号", 12, Palette.muted, true).apply { setPadding(0, ui.dp(10), 0, 0) })
            addView(fakeCodeEdit, LinearLayout.LayoutParams(-1, -2))
        })

        into.addView(ui.actionCard("保存版本伪装", "写入目标应用，重启后生效") {
            val fakeName = fakeNameEdit.text.toString().trim()
            val fakeCode = fakeCodeEdit.text.toString().trim().toIntOrNull() ?: 0
            if (fakeName.isEmpty() && fakeCode <= 0) {
                host.toast("至少填一个伪装版本名或版本号")
                return@actionCard
            }
            val escapedName = fakeName.replace("'", "'\\''")
            val command = "mkdir -p /data/data/$pkg/files; " +
                "printf '%s\\n' 'enabled=1' 'fakeName=$escapedName' 'fakeCode=$fakeCode' " +
                "> /data/data/$pkg/files/selfxposed_update_block.conf; " +
                "chown \$(stat -c %u:%g /data/data/$pkg) /data/data/$pkg/files/selfxposed_update_block.conf; " +
                "chmod 660 /data/data/$pkg/files/selfxposed_update_block.conf; echo ok"
            val out = RootShell.exec(command)
            TargetFiles.invalidate(pkg)
            host.toast(
                if (out.contains("ok")) "版本伪装已保存，重启目标应用生效"
                else "写入失败，检查 Root / Shizuku 授权"
            )
        })

        into.addView(ui.hint(
            "说明：只能骗过「应用自己查询版本」的情况。\n" +
                "如果更新提示是服务器下发（客户端上报版本、服务器决定），伪装可能无效，" +
                "这时请用「拦截弹窗」按关键字把更新弹窗拦掉。"
        ))
        into.addView(ui.actionCard("查看日志", "运行记录与错误信息") { host.openLogs(pkg, name) })
    }
}
