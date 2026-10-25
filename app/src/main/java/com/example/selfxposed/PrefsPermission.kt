package com.example.selfxposed

import android.content.Context
import java.io.File

/**
 * 模块的配置写在 SharedPreferences 里（默认 0600）。
 * LSPosed 的文件服务在读取时可能会检查可读权限，而 targetSdk 35 的目标应用
 * 又没法通过 ContentProvider 拿到配置，所以这里把文件放开为 0644。
 * 数据本身只是界面配置，不含敏感信息。
 */
object PrefsPermission {
    fun makeWorldReadable(context: Context) {
        try {
            val file = File(
                "/data/data/${context.packageName}/shared_prefs/${ConfigClient.PREFS_NAME}.xml"
            )
            if (file.exists()) file.setReadable(true, false)
        } catch (_: Throwable) {
        }
    }
}
