package com.example.selfxposed

import android.content.Context
import java.io.File

/**
 * “后台常驻”开关的存储，供界面、服务与开机广播共用。
 *
 * 注意：不要用 SharedPreferences。本项目里 SP 文件被 PrefsPermission 改过权限，
 * 实测 commit() 也不落盘（内存生效、重启丢失）。这里改成普通文件，和 WorkMode 一致。
 */
object KeepAlive {
    private const val FILE = "selfxposed_keepalive.conf"

    private fun fileOf(context: Context) = File(context.filesDir, FILE)

    fun isEnabled(context: Context): Boolean = try {
        fileOf(context).exists()
    } catch (_: Throwable) {
        false
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        try {
            val file = fileOf(context)
            if (enabled) {
                file.parentFile?.mkdirs()
                file.writeText("1\n")
            } else {
                file.delete()
            }
        } catch (_: Throwable) {
        }
    }
}
